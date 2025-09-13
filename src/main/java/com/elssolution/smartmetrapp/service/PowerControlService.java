package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.SmSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Acrel-only power control.
 *
 * On each tick:
 *   1) Clone the latest Acrel raw frame (pass-through by default).
 *   2) If no compensation requested (override OFF or <=0) → pure pass-through (no edits).
 *   3) If compensation requested but frame is stale/offline → zero I/P for safety.
 *   4) Else add compensateKw across *alive* phases (V >= phaseMinVolt):
 *        - Currents at 100/101/102 (raw: 0.01 A * CT).
 *        - Per-phase P at 356/358/360 (raw: W / (PT * CT)).
 *        - Total P at 362 (same raw).
 *
 * Acrel registers:
 *   V L1..L3:      97..99   (u16, value = 0.1 V * PT)
 *   I L1..L3:     100..102  (u16, value = 0.01 A * CT)
 *   Hz:             119     (u16, value = 0.01 Hz)
 *   P L1/L2/L3:   356/358/360 (i32, raw = W / (PT * CT), MSW first)
 *   P Total:       362        (i32, raw = W / (PT * CT), MSW first)
 */
@Slf4j
@Component
public class PowerControlService {

    // ====== Config (from application.yml) ======
    @Value("${smartmetr.scale.pt:1.0}")        private double pt;                // PT ratio
    @Value("${smartmetr.scale.ct:1.0}")        private double ct;                // CT ratio
    @Value("${smartmetr.cosPhiMin:0.95}")      private double minPf;             // lower bound for PF
    @Value("${smartmetr.staleToZeroMs:300000}")private long   maxAgeMs;          // data freshness gate (ms)

    /** A phase is considered "alive" if its phase-to-neutral V >= this. */
    @Value("${smartmetr.phaseMinVolt:100.0}")  private double phaseMinVolt;

    /** Guard in I = P/(V*pf) to avoid huge currents when V is tiny. */
    @Value("${smartmetr.safeDivMinVolt:100.0}")private double safeDivMinVolt;

    // ====== Acrel register addresses ======
    private static final int REG_V1   =  97;
    private static final int REG_V2   =  98;
    private static final int REG_V3   =  99;
    private static final int REG_I1   = 100;
    private static final int REG_I2   = 101;
    private static final int REG_I3   = 102;
    private static final int REG_P1   = 356; // i32 MSW
    private static final int REG_P2   = 358; // i32 MSW
    private static final int REG_P3   = 360; // i32 MSW
    private static final int REG_PTOT = 362; // i32 MSW

    /**
     * Build the output frame for the inverter using Acrel native registers only.
     *
     * @param snapshot     last meter frame (Acrel raw registers)
     * @param compensateKw positive kW we want to add to site load (split across phases)
     * @return the register image to expose to the inverter (same layout as input, with edits)
     */
    public short[] prepareOutputWords(SmSnapshot snapshot, double compensateKw) {
        // 1) Start from meter words (pass-through everything we don’t touch)
        short[] base = (snapshot != null && snapshot.data != null) ? snapshot.data : new short[0];
        short[] out  = ensureCapacity(base, 364);   // need at least up to reg 363

        // Normalize scalers (avoid 0/NaN)
        final double PT = (pt > 0 && Double.isFinite(pt)) ? pt : 1.0;
        final double CT = (ct > 0 && Double.isFinite(ct)) ? ct : 1.0;

        // 2) PURE PASS-THROUGH when no compensation requested (override OFF)
        if (!Double.isFinite(compensateKw) || compensateKw <= 0.0) {
            return out;
        }

        // 3) For active compensation: block stale/offline frames
        final long age = (snapshot == null || snapshot.updatedAtMs == 0)
                ? Long.MAX_VALUE
                : (System.currentTimeMillis() - snapshot.updatedAtMs);

        if (age > maxAgeMs || acrelOffline(out, PT)) {
            zeroCurrentsAndPowers(out);
            return out;
        }

        // Phase voltages in Volts
        final double v1 = 0.1 * u16(out, REG_V1) * PT;
        final double v2 = 0.1 * u16(out, REG_V2) * PT;
        final double v3 = 0.1 * u16(out, REG_V3) * PT;

        // Decide which phases are alive
        boolean a1 = v1 >= phaseMinVolt;
        boolean a2 = v2 >= phaseMinVolt;
        boolean a3 = v3 >= phaseMinVolt;
        int alive = (a1?1:0) + (a2?1:0) + (a3?1:0);

        if (alive == 0) {
            // meter says all phases are essentially dead → fail-safe
            zeroCurrentsAndPowers(out);
            return out;
        }

        // 4) Apply compensation across alive phases (import is NEGATIVE on Acrel)
        final double pf = clamp(minPf, 0.1, 1.0);
        final double totalW = compensateKw * 1000.0;
        final double perAliveW = -(totalW / alive);        // negative = "more import"

        double sumAddW = 0.0;

        if (a1) {
            double dI = bumpPhaseCurrent(out, REG_I1, v1, perAliveW, pf, CT);
            bumpPhasePower(out, REG_P1, perAliveW, PT, CT);
            sumAddW += perAliveW;
            if (log.isTraceEnabled()) log.trace("L1: V={}V ΔI≈{}A addW={}W", to2(v1), to2(dI), Math.round(perAliveW));
        }
        if (a2) {
            double dI = bumpPhaseCurrent(out, REG_I2, v2, perAliveW, pf, CT);
            bumpPhasePower(out, REG_P2, perAliveW, PT, CT);
            sumAddW += perAliveW;
            if (log.isTraceEnabled()) log.trace("L2: V={}V ΔI≈{}A addW={}W", to2(v2), to2(dI), Math.round(perAliveW));
        }
        if (a3) {
            double dI = bumpPhaseCurrent(out, REG_I3, v3, perAliveW, pf, CT);
            bumpPhasePower(out, REG_P3, perAliveW, PT, CT);
            sumAddW += perAliveW;
            if (log.isTraceEnabled()) log.trace("L3: V={}V ΔI≈{}A addW={}W", to2(v3), to2(dI), Math.round(perAliveW));
        }

        // Bump total power (keep sign convention)
        final double pTotW    = i32be(out, REG_PTOT) * PT * CT;
        final double pTotWNew = pTotW + sumAddW;
        writeI32be(out, REG_PTOT, toRawPower(pTotWNew, PT, CT));

        if (log.isDebugEnabled()) {
            log.debug("compensate={}kW (alive phases={}) → ~{}W per-alive @pf={} → ΔPtot={}W",
                    to3(compensateKw), alive, Math.round(-perAliveW), to2(pf), Math.round(sumAddW));
        }

        return out;
    }

    // ====== Acrel helpers ======

    private boolean acrelOffline(short[] w, double PT) {
        final double v1 = 0.1 * u16(w, REG_V1) * PT;
        final double v2 = 0.1 * u16(w, REG_V2) * PT;
        final double v3 = 0.1 * u16(w, REG_V3) * PT;
        return (v1 < 1.0 && v2 < 1.0 && v3 < 1.0);
    }

    private void zeroCurrentsAndPowers(short[] w) {
        writeU16(w, REG_I1, 0);
        writeU16(w, REG_I2, 0);
        writeU16(w, REG_I3, 0);
        writeI32be(w, REG_P1,   0);
        writeI32be(w, REG_P2,   0);
        writeI32be(w, REG_P3,   0);
        writeI32be(w, REG_PTOT, 0);
    }

    /** Add current to one phase: ΔI = |P| / (max(safeDivMinVolt, V*PF)), raw = 0.01 A * CT. Returns ΔI (A). */
    private double bumpPhaseCurrent(short[] w, int regI, double vVolt, double addW, double pf, double CT) {
        final double denomV = Math.max(safeDivMinVolt, vVolt * pf);
        final int    rawNow = u16(w, regI);               // raw = 0.01 A * CT
        final double iA     = 0.01 * rawNow * CT;         // A
        final double addA   = Math.abs(addW) / denomV;    // always increase magnitude
        final double iNew   = Math.max(0.0, iA + addA);
        final int    rawNew = (int) Math.round(iNew / (0.01 * CT));
        writeU16(w, regI, clampU16(rawNew));
        return addA;
    }

    // Bump per-phase powers (356/358/360). Units: raw = W / (PT * CT). addW is NEGATIVE → more import
    private void bumpPhasePower(short[] w, int regPmsw, double addW, double PT, double CT) {
        final double pW   = i32be(w, regPmsw) * PT * CT;
        final double pNew = pW + addW;
        writeI32be(w, regPmsw, toRawPower(pNew, PT, CT));
    }

    private int toRawPower(double watts, double PT, double CT) {
        final double den = Math.max(1e-6, PT * CT);
        final double raw = watts / den;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (raw < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.round(raw);
    }

    // ====== raw register utils ======

    private static int u16(short[] a, int i) {
        if (a == null || i < 0 || i >= a.length) return 0;
        return a[i] & 0xFFFF;
    }
    private static void writeU16(short[] a, int i, int val) {
        if (a == null || i < 0 || i >= a.length) return;
        a[i] = (short)(val & 0xFFFF);
    }
    private static int i32be(short[] a, int msw) {
        if (a == null || msw < 0 || msw + 1 >= a.length) return 0;
        int hi = u16(a, msw);
        int lo = u16(a, msw + 1);
        return (hi << 16) | lo; // signed int result (two's complement preserved)
    }
    private static void writeI32be(short[] a, int msw, int value) {
        if (a == null || msw < 0 || msw + 1 >= a.length) return;
        a[msw]     = (short)((value >>> 16) & 0xFFFF);
        a[msw + 1] = (short)( value         & 0xFFFF);
    }

    private static int clampU16(int v) {
        if (v < 0) return 0;
        if (v > 0xFFFF) return 0xFFFF;
        return v;
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static short[] ensureCapacity(short[] src, int minLen) {
        if (src == null) return new short[minLen];
        if (src.length >= minLen) return src.clone();
        short[] dst = new short[minLen];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    // ====== tiny format helpers for logs ======
    private static String to2(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
    private static String to3(double v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }
}
