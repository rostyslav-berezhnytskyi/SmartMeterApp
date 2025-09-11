package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.MeterDecoder;
import com.elssolution.smartmetrapp.domain.MeterRegisterMap;
import com.elssolution.smartmetrapp.domain.SmSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static com.elssolution.smartmetrapp.domain.Maths.clamp;
import static com.elssolution.smartmetrapp.domain.Maths.safeDiv;

/**
 * Builds the *outgoing* Modbus image for the inverter.
 *
 *  - acrel:  work on RAW Acrel registers (u16 + i32 at fixed addresses).
 *  - eastron:work on float words per MeterRegisterMap (your legacy layout).
 */
@Slf4j
@Component
public class PowerControlService {

    private final MeterDecoder codec;
    private final MeterRegisterMap map;

    public PowerControlService(MeterDecoder codec, MeterRegisterMap map) {
        this.codec = codec;
        this.map   = map;
    }

    // Mode & scaling
    @Value("${smartmetr.kind:acrel}") private String meterKind;
    @Value("${smartmetr.scale.pt:1.0}") private double acrelPt;
    @Value("${smartmetr.scale.ct:1.0}") private double acrelCt;

    // Generic knobs
    @Value("${smartmetr.maxDataAgeMs:300000}") private long maxDataAgeMs;
    @Value("${smartmetr.rampToZeroMs:2000}")   private long rampToZeroMs;
    @Value("${smartmetr.minPowerFactor:0.95}") private double minPowerFactor;

    // Acrel addresses we use
    private static final int ACREL_V1 = 97, ACREL_V2 = 98, ACREL_V3 = 99;     // u16 *0.1V*PT
    private static final int ACREL_I1 = 100, ACREL_I2 = 101, ACREL_I3 = 102;  // u16 *0.01A*CT
    private static final int ACREL_HZ = 119;                                   // u16 *0.01Hz
    private static final int ACREL_P1 = 356, ACREL_P2 = 358, ACREL_P3 = 360, ACREL_PTOT = 362; // i32 (see below)

    /** Build output words. */
    public short[] prepareOutputWords(SmSnapshot snapshot, double compensateKw) {
        if ("acrel".equalsIgnoreCase(meterKind)) {
            return buildAcrelOutput(snapshot, compensateKw);
        } else {
            return buildEastronOutput(snapshot, compensateKw);
        }
    }

    // ---------------- ACREL PATH (raw) ----------------

    private short[] buildAcrelOutput(SmSnapshot s, double compensateKw) {
        final int len = (s != null && s.data != null) ? s.data.length : 400;
        short[] out = (s != null && s.data != null) ? s.data.clone() : new short[len];

        if (!isRecent(s, maxDataAgeMs) || acrelOffline(out)) {
            // zero I & P; keep voltages so inverter can tell offline vs dead
            writeU16(out, ACREL_I1, 0);
            writeU16(out, ACREL_I2, 0);
            writeU16(out, ACREL_I3, 0);
            writeI32BE(out, ACREL_P1, 0);
            writeI32BE(out, ACREL_P2, 0);
            writeI32BE(out, ACREL_P3, 0);
            writeI32BE(out, ACREL_PTOT, 0);
            return out;
        }

        if (Double.isNaN(compensateKw) || compensateKw < 0) compensateKw = 0.0;
        if (compensateKw == 0.0) return out; // pure pass-through

        final double pf = clamp(minPowerFactor, 0.1, 1.0);
        final double perPhaseW = (compensateKw * 1000.0) / 3.0; // W per phase to add

        // Read present V/I/P (decoded)
        double v1 = 0.1 * u16(out, ACREL_V1) * acrelPt;
        double v2 = 0.1 * u16(out, ACREL_V2) * acrelPt;
        double v3 = 0.1 * u16(out, ACREL_V3) * acrelPt;

        double i1 = 0.01 * u16(out, ACREL_I1) * acrelCt;
        double i2 = 0.01 * u16(out, ACREL_I2) * acrelCt;
        double i3 = 0.01 * u16(out, ACREL_I3) * acrelCt;

        // Powers in W: NOTE decode: pW = raw * PT * CT  (because spec said i32*0.001 kW *1000 = raw * PT * CT)
        double p1W = (double) i32be(out, ACREL_P1)  * acrelPt * acrelCt;
        double p2W = (double) i32be(out, ACREL_P2)  * acrelPt * acrelCt;
        double p3W = (double) i32be(out, ACREL_P3)  * acrelPt * acrelCt;
        double ptW = (double) i32be(out, ACREL_PTOT) * acrelPt * acrelCt;

        // Add currents coherently: ΔI = ΔW / (V * pf)
        double addI1 = safeDiv(perPhaseW, Math.max(100.0, v1 * pf));
        double addI2 = safeDiv(perPhaseW, Math.max(100.0, v2 * pf));
        double addI3 = safeDiv(perPhaseW, Math.max(100.0, v3 * pf));
        i1 += addI1; i2 += addI2; i3 += addI3;

        // Write back currents → raw u16 where Ia = 0.01 * raw * CT  => raw = Ia * 100 / CT
        writeU16(out, ACREL_I1, clampU16(Math.round(i1 * 100.0 / Math.max(1e-9, acrelCt))));
        writeU16(out, ACREL_I2, clampU16(Math.round(i2 * 100.0 / Math.max(1e-9, acrelCt))));
        writeU16(out, ACREL_I3, clampU16(Math.round(i3 * 100.0 / Math.max(1e-9, acrelCt))));

        // Bump per-phase powers in W
        p1W += perPhaseW; p2W += perPhaseW; p3W += perPhaseW; ptW += (compensateKw * 1000.0);

        // Convert W back to raw i32: raw = pW / (PT * CT)
        writeI32BE(out, ACREL_P1,  clampI32(Math.round(p1W / Math.max(1e-9, acrelPt * acrelCt))));
        writeI32BE(out, ACREL_P2,  clampI32(Math.round(p2W / Math.max(1e-9, acrelPt * acrelCt))));
        writeI32BE(out, ACREL_P3,  clampI32(Math.round(p3W / Math.max(1e-9, acrelPt * acrelCt))));
        writeI32BE(out, ACREL_PTOT,clampI32(Math.round(ptW / Math.max(1e-9, acrelPt * acrelCt))));

        log.debug("acrel_comp Δ={}kW → +{}W/phase; pf={} → ΔI≈[{},{},{}]A",
                compensateKw, Math.round(perPhaseW), pf,
                String.format("%.2f",addI1), String.format("%.2f",addI2), String.format("%.2f",addI3));

        return out;
    }

    private boolean acrelOffline(short[] w) {
        // consider offline if all three voltages <1V after scaling
        double v1 = 0.1 * u16(w, ACREL_V1) * acrelPt;
        double v2 = 0.1 * u16(w, ACREL_V2) * acrelPt;
        double v3 = 0.1 * u16(w, ACREL_V3) * acrelPt;
        return (v1 < 1.0 && v2 < 1.0 && v3 < 1.0);
    }

    // ---------------- EASTRON PATH (float image) ----------------

    private short[] buildEastronOutput(SmSnapshot s, double compensateKw) {
        // Keep your previous behavior verbatim
        final int len = (s != null && s.data != null) ? s.data.length : 124;
        short[] out = (s != null && s.data != null) ? s.data.clone() : new short[len];

        if (!isRecent(s, maxDataAgeMs) || eastronOffline(out)) {
            float zero = 0f;
            writeFloatIfPresent(out, map.pTotal(), zero);
            writeFloatIfPresent(out, map.pL1(), zero);
            writeFloatIfPresent(out, map.pL2(), zero);
            writeFloatIfPresent(out, map.pL3(), zero);
            writeFloatIfPresent(out, map.iL1(), zero);
            writeFloatIfPresent(out, map.iL2(), zero);
            writeFloatIfPresent(out, map.iL3(), zero);
            return out;
        }

        if (Double.isNaN(compensateKw) || compensateKw < 0) compensateKw = 0.0;
        if (compensateKw == 0.0) return out;

        final double pf = clamp(minPowerFactor, 0.1, 1.0);
        final double perPhaseKw = compensateKw / 3.0;

        double v1 = readFloat(out, map.vL1());
        double v2 = readFloat(out, map.vL2());
        double v3 = readFloat(out, map.vL3());
        double i1 = readFloat(out, map.iL1());
        double i2 = readFloat(out, map.iL2());
        double i3 = readFloat(out, map.iL3());

        double addI1 = safeDiv((perPhaseKw * 1000.0), Math.max(100.0, v1 * pf));
        double addI2 = safeDiv((perPhaseKw * 1000.0), Math.max(100.0, v2 * pf));
        double addI3 = safeDiv((perPhaseKw * 1000.0), Math.max(100.0, v3 * pf));

        writeFloatIfPresent(out, map.iL1(), (float) (i1 + addI1));
        writeFloatIfPresent(out, map.iL2(), (float) (i2 + addI2));
        writeFloatIfPresent(out, map.iL3(), (float) (i3 + addI3));

        float addW = (float) (perPhaseKw * 1000.0);
        bumpFloat(out, map.pL1(), addW);
        bumpFloat(out, map.pL2(), addW);
        bumpFloat(out, map.pL3(), addW);
        bumpFloat(out, map.pTotal(), (float) (compensateKw * 1000.0));

        return out;
    }

    private boolean eastronOffline(short[] w) {
        double v1 = readFloat(w, map.vL1());
        double v2 = readFloat(w, map.vL2());
        double v3 = readFloat(w, map.vL3());
        return (v1 < 1.0 && v2 < 1.0 && v3 < 1.0);
    }

    // ---------------- Utilities ----------------

    private boolean isRecent(SmSnapshot s, long maxAgeMs) {
        return (s != null && s.updatedAtMs > 0 && (System.currentTimeMillis() - s.updatedAtMs) <= maxAgeMs);
    }

    // Acrel raw helpers
    private static int u16(short[] a, int idx) {
        if (a == null || idx < 0 || idx >= a.length) return 0;
        return a[idx] & 0xFFFF;
    }
    private static void writeU16(short[] a, int idx, int u) {
        if (a == null || idx < 0 || idx >= a.length) return;
        a[idx] = (short) (u & 0xFFFF);
    }
    private static int i32be(short[] a, int mswIdx) {
        int hi = u16(a, mswIdx);
        int lo = u16(a, mswIdx + 1);
        return (hi << 16) | lo;
    }
    private static void writeI32BE(short[] a, int mswIdx, int val) {
        if (a == null || mswIdx < 0 || mswIdx + 1 >= a.length) return;
        a[mswIdx]     = (short) ((val >>> 16) & 0xFFFF);
        a[mswIdx + 1] = (short) (val & 0xFFFF);
    }
    private static int clampU16(long v) {
        return (int) Math.max(0, Math.min(0xFFFFL, v));
    }
    private static int clampI32(long v) {
        long min = 0x80000000L; // signed
        long max = 0x7FFFFFFFL;
        if (v < min) v = min;
        if (v > max) v = max;
        return (int) v;
    }

    // Float helpers (Eastron)
    private double readFloat(short[] w, int off) {
        if (off < 0 || w == null || off + 1 >= w.length) return 0.0;
        return codec.readFloatOrDefault(w, off, 0f);
    }
    private void writeFloatIfPresent(short[] w, int off, float val) {
        if (off < 0 || w == null || off + 1 >= w.length) return;
        codec.writeFloat(w, off, val);
    }
    private void bumpFloat(short[] w, int off, float add) {
        if (off < 0 || w == null || off + 1 >= w.length) return;
        float cur = codec.readFloatOrDefault(w, off, 0f);
        codec.writeFloat(w, off, cur + add);
    }
}
