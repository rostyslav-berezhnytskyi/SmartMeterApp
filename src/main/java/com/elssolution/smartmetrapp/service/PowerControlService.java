package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.SmSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Acrel-only power control.
 *
 * What this class does on each tick:
 *   1) Clone the latest Acrel raw frame from the meter (so we keep all "pass-through" data).
 *   2) If the frame is stale or looks offline (no voltage) -> set Acrel currents & powers to 0.
 *   3) Otherwise, add the requested compensateKw evenly across 3 phases:
 *        - Bump phase currents at regs 100/101/102 (raw: 0.01 A * CT).
 *        - Bump per-phase active power at regs 356/358/360 (raw: W / (PT * CT)).
 *        - Bump total active power at reg 362 (raw: W / (PT * CT)).
 *
 * Acrel register map (native units we use):
 *   V phase L1..L3:   97..99   (u16, value = 0.1 V * PT)
 *   I phase L1..L3:  100..102  (u16, value = 0.01 A * CT)
 *   P L1/L2/L3/Total:356/358/360/362 (i32 signed, raw = W / (PT * CT), MSW first)
 */
@Slf4j
@Component
public class PowerControlService {

    // ====== Config (from application.yml) ======
    @Value("${smartmetr.scale.pt:1.0}")  private double pt;         // PT ratio
    @Value("${smartmetr.scale.ct:1.0}")  private double ct;         // CT ratio
    @Value("${smartmetr.cosPhiMin:0.95}") private double minPf;// lower bound for PF
    @Value("${smartmetr.staleToZeroMs:300000}") private long  maxAgeMs; // data freshness gate (ms)

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
        short[] out = ensureCapacity(base, 364);   // need at least up to reg 363 (0-based 362/363 for total P)

        // >>> PURE PASS-THROUGH when no compensation requested <<<
        // This path is hit when solis.overrideEnabled=false (getCurrentDeltaKw() returns 0).
        if (!Double.isFinite(compensateKw) || compensateKw <= 0.0) {
            return out;
        }

        long age = (snapshot == null || snapshot.updatedAtMs == 0)
                ? Long.MAX_VALUE
                : (System.currentTimeMillis() - snapshot.updatedAtMs);

        // If stale or looks offline, zero I/P for safety (only in active override mode)
        if (age > maxAgeMs || acrelOffline(out)) {
            zeroCurrentsAndPowers(out);
            return out;
        }



        // 3) Apply compensation evenly across 3 phases
        final double pf = clamp(minPf, 0.1, 1.0);
        final double perPhaseW = (compensateKw * 1000.0) / 3.0;

        // Phase voltages in Volts
        double v1 = 0.1 * u16(out, 97) * pt;
        double v2 = 0.1 * u16(out, 98) * pt;
        double v3 = 0.1 * u16(out, 99) * pt;

        // Bump currents (regs 100..102). Units: raw = 0.01 A * CT
        bumpPhaseCurrent(out, 100, v1, perPhaseW, pf);
        bumpPhaseCurrent(out, 101, v2, perPhaseW, pf);
        bumpPhaseCurrent(out, 102, v3, perPhaseW, pf);

        // Bump per-phase powers (356/358/360). Units: raw = W / (PT * CT)
        bumpPhasePower(out, 356, perPhaseW);
        bumpPhasePower(out, 358, perPhaseW);
        bumpPhasePower(out, 360, perPhaseW);

        // Bump total power (362)
        double pTotW = i32be(out, 362) * pt * ct;
        double pTotWNew = pTotW + (3 * perPhaseW);
        writeI32be(out, 362, toRawPower(pTotWNew));

        return out;
    }

    // ====== Acrel helpers ======

    private boolean acrelOffline(short[] w) {
        double v1 = 0.1 * u16(w, 97) * pt;
        double v2 = 0.1 * u16(w, 98) * pt;
        double v3 = 0.1 * u16(w, 99) * pt;
        return (v1 < 1.0 && v2 < 1.0 && v3 < 1.0);
    }

    private void zeroCurrentsAndPowers(short[] w) {
        // I L1..L3
        writeU16(w, 100, 0);
        writeU16(w, 101, 0);
        writeU16(w, 102, 0);
        // P L1/L2/L3/Total
        writeI32be(w, 356, 0);
        writeI32be(w, 358, 0);
        writeI32be(w, 360, 0);
        writeI32be(w, 362, 0);
    }

    /** Add current to one phase: ΔI = P / (V * PF), then convert to raw (0.01 A * CT). */
    private void bumpPhaseCurrent(short[] w, int regI, double vVolt, double addW, double pf) {
        int rawNow = u16(w, regI);                  // raw = 0.01 A * CT
        double iA  = 0.01 * rawNow * ct;            // A
        double addA = addW / Math.max(100.0, vVolt * pf);
        double iNew = Math.max(0.0, iA + addA);
        int rawNew = (int)Math.round(iNew / (0.01 * ct));
        writeU16(w, regI, clampU16(rawNew));
    }

    /** Add power to one phase: raw = W/(PT*CT). */
    private void bumpPhasePower(short[] w, int regPmsw, double addW) {
        double pW = i32be(w, regPmsw) * pt * ct;
        double pNew = pW + addW;
        writeI32be(w, regPmsw, toRawPower(pNew));
    }

    private int toRawPower(double watts) {
        double den = Math.max(1e-6, pt * ct);
        double raw = watts / den;
        if (raw > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (raw < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int)Math.round(raw);
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
}
