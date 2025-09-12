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
 *   4) Else add compensateKw across phases:
 *        - Currents at 100/101/102 (raw: 0.01 A * CT).
 *        - Per-phase P at 356/358/360 (raw: W / (PT * CT)).
 *        - Total P at 362 (same raw).
 *
 * Acrel registers:
 *   V L1..L3:    97..99   (u16, value = 0.1 V * PT)
 *   I L1..L3:   100..102  (u16, value = 0.01 A * CT)
 *   Hz:           119     (u16, value = 0.01 Hz)  // not used here, but documented
 *   P L1/L2/L3: 356/358/360 (i32, raw = W / (PT * CT), MSW first)
 *   P Total:     362        (i32, raw = W / (PT * CT), MSW first)
 */
@Slf4j
@Component
public class PowerControlService {

    // ====== Config (from application.yml) ======
    @Value("${smartmetr.scale.pt:1.0}")   private double pt;            // PT ratio
    @Value("${smartmetr.scale.ct:1.0}")   private double ct;            // CT ratio
    @Value("${smartmetr.cosPhiMin:0.95}") private double minPf;         // lower bound for PF
    @Value("${smartmetr.staleToZeroMs:300000}") private long  maxAgeMs; // data freshness gate (ms)

    // ====== Acrel register addresses (named for clarity) ======
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

        // 2) PURE PASS-THROUGH when no compensation requested (override OFF)
        //    This keeps the inverter seeing exactly what the meter reports.
        if (!Double.isFinite(compensateKw) || compensateKw <= 0.0) {
            return out;
        }

        // 3) For active compensation: block stale/offline frames
        final long age = (snapshot == null || snapshot.updatedAtMs == 0)
                ? Long.MAX_VALUE
                : (System.currentTimeMillis() - snapshot.updatedAtMs);

        if (age > maxAgeMs || acrelOffline(out)) {
            zeroCurrentsAndPowers(out);
            return out;
        }

        // 4) Apply compensation evenly across 3 phases
        final double pf         = clamp(minPf, 0.1, 1.0);
        final double perPhaseW  = (compensateKw * 1000.0) / 3.0;

        // Phase voltages in Volts
        final double v1 = 0.1 * u16(out, REG_V1) * pt;
        final double v2 = 0.1 * u16(out, REG_V2) * pt;
        final double v3 = 0.1 * u16(out, REG_V3) * pt;

        // Bump currents (regs 100..102). Units: raw = 0.01 A * CT
        final double dI1 = bumpPhaseCurrent(out, REG_I1, v1, perPhaseW, pf);
        final double dI2 = bumpPhaseCurrent(out, REG_I2, v2, perPhaseW, pf);
        final double dI3 = bumpPhaseCurrent(out, REG_I3, v3, perPhaseW, pf);

        // Bump per-phase powers (356/358/360). Units: raw = W / (PT * CT)
        bumpPhasePower(out, REG_P1, perPhaseW);
        bumpPhasePower(out, REG_P2, perPhaseW);
        bumpPhasePower(out, REG_P3, perPhaseW);

        // Bump total power (362)
        final double pTotW    = i32be(out, REG_PTOT) * pt * ct;
        final double pTotWNew = pTotW + (3 * perPhaseW);
        writeI32be(out, REG_PTOT, toRawPower(pTotWNew));

        if (log.isDebugEnabled()) {
            log.debug("compensate={}kW → +{}W/phase @pf={} → ΔI≈[{},{},{}] A",
                    to3(compensateKw), Math.round(perPhaseW), to2(pf), to2(dI1), to2(dI2), to2(dI3));
        }

        return out;
    }

    // ====== Acrel helpers ======

    private boolean acrelOffline(short[] w) {
        final double v1 = 0.1 * u16(w, REG_V1) * pt;
        final double v2 = 0.1 * u16(w, REG_V2) * pt;
        final double v3 = 0.1 * u16(w, REG_V3) * pt;
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

    /** Add current to one phase: ΔI = P / (V * PF), then convert to raw (0.01 A * CT). Returns ΔI (A). */
    private double bumpPhaseCurrent(short[] w, int regI, double vVolt, double addW, double pf) {
        final int    rawNow = u16(w, regI);           // raw = 0.01 A * CT
        final double iA     = 0.01 * rawNow * ct;     // A
        final double addA   = addW / Math.max(100.0, vVolt * pf);
        final double iNew   = Math.max(0.0, iA + addA);
        final int    rawNew = (int) Math.round(iNew / (0.01 * ct));
        writeU16(w, regI, clampU16(rawNew));
        return addA;
    }

    /** Add power to one phase: raw = W/(PT*CT). */
    private void bumpPhasePower(short[] w, int regPmsw, double addW) {
        final double pW  = i32be(w, regPmsw) * pt * ct;
        final double pNew = pW + addW;
        writeI32be(w, regPmsw, toRawPower(pNew));
    }

    private int toRawPower(double watts) {
        final double den = Math.max(1e-6, pt * ct);
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
        return (hi << 16) | lo;
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
