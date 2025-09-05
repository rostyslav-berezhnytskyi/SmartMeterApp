package com.elssolution.smartmetrapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the Modbus output words for the inverter:
 * - if the smart-meter data is too old -> drive currents & powers to 0 (keep voltages)
 * - if fresh -> add compensated power evenly across 3 phases (adjust currents; bump total power)
 *
 * Notes:
 * - We assume a 3-phase layout by default. If your map doesn’t expose L2/L3 yet, those offsets
 *   can be set to -1, and we will skip them safely.
 * - All calculations use IEEE754 floats on the wire (handled by MeterCodec).
 */
@Slf4j
@Component
public class PowerController {

    private final MeterCodec codec;
    private final MeterMap registerMap;

    public PowerController(MeterCodec codec, MeterMap registerMap) {
        this.codec = codec;
        this.registerMap = registerMap;
    }

    @Value("${smartmetr.maxDataAgeMs:300000}") // 5 minutes default
    private long maxDataAgeMs;

    @Value("${smartmetr.rampToZeroMs:2000}")
    private long rampToZeroMs;

    /** Minimum power factor to use when converting kW→A. Clamped to [0.1, 1.0]. */
    @Value("${smartmetr.minPowerFactor:0.95}")
    private double minPowerFactor;

    /**
     * Build the words to publish to the inverter.
     * @param snapshot last good meter read (raw words + timestamp)
     * @param compensateKw how many kW we want to absorb (>= 0)
     */
    public short[] prepareOutputWords(SmSnapshot snapshot, double compensateKw) {
        // Allocate from snapshot if available, otherwise a sensible default write window
        final int len = (snapshot != null && snapshot.data != null)
                ? snapshot.data.length : 100; // matches WRITE_REG_COUNT in feeder
        short[] outWords = (snapshot != null && snapshot.data != null)
                ? snapshot.data.clone()
                : new short[len];

        if (!isRecent(snapshot, maxDataAgeMs)) {
            long age = (snapshot == null) ? -1L : (System.currentTimeMillis() - snapshot.updatedAtMs);
            log.info("meter_data_stale ageMs={} → driving currents & powers to 0", age);
            driveToNeutral(outWords);
            return outWords;
        }

        // Defensive clamp
        if (Double.isNaN(compensateKw) || compensateKw < 0.0) compensateKw = 0.0;

        if (compensateKw > 0.0) {
            applyCompensationThreePhase(outWords, compensateKw);
        }
        return outWords;
    }

    private boolean isRecent(SmSnapshot s, long maxAgeMs) {
        if (s == null || s.updatedAtMs <= 0) return false;
        return (System.currentTimeMillis() - s.updatedAtMs) <= maxAgeMs;
    }

    /** Zero active power(s) & current(s); keep voltages (0 V can be treated as a fault). */
    private void driveToNeutral(short[] words) {
        float pTot = readSafe(words, registerMap.pTotal());
        float i1   = readSafe(words, registerMap.iL1());
        float i2   = readSafe(words, registerMap.iL2());
        float i3   = readSafe(words, registerMap.iL3());

        final float pTarget = 0f, iTarget = 0f;
        if (rampToZeroMs > 0) {
            pTot = rampTowards(pTot, pTarget, rampToZeroMs);
            i1   = rampTowards(i1,   iTarget, rampToZeroMs);
            i2   = rampTowards(i2,   iTarget, rampToZeroMs);
            i3   = rampTowards(i3,   iTarget, rampToZeroMs);
        } else {
            pTot = pTarget; i1 = iTarget; i2 = iTarget; i3 = iTarget;
        }

        writeIfPresent(words, registerMap.pTotal(), pTot);
        writeIfPresent(words, registerMap.iL1(),   i1);
        writeIfPresent(words, registerMap.iL2(),   i2);
        writeIfPresent(words, registerMap.iL3(),   i3);

        writeIfPresent(words, registerMap.pL1(), 0f);
        writeIfPresent(words, registerMap.pL2(), 0f);
        writeIfPresent(words, registerMap.pL3(), 0f);
    }

    /**
     * Split compensateKw evenly across 3 phases and adjust currents.
     * Bump total active power by (compensateKw * 1000).
     */
    private void applyCompensationThreePhase(short[] words, double compensateKw) {
        final double pf = clamp(minPowerFactor, 0.1, 1.0);
        final double perPhaseKw = compensateKw / 3.0;

        // L1
        double v1 = readSafe(words, registerMap.vL1());
        double i1 = readSafe(words, registerMap.iL1());
        double addI1 = (perPhaseKw * 1000.0) / Math.max(100.0, v1 * pf);
        i1 += addI1;
        writeIfPresent(words, registerMap.iL1(), (float) i1);

        // L2 (if mapped)
        if (hasRegister(registerMap.vL2()) && hasRegister(registerMap.iL2())) {
            double v2 = readSafe(words, registerMap.vL2());
            double i2 = readSafe(words, registerMap.iL2());
            double addI2 = (perPhaseKw * 1000.0) / Math.max(100.0, v2 * pf);
            i2 += addI2;
            writeIfPresent(words, registerMap.iL2(), (float) i2);
        }

        // L3 (if mapped)
        if (hasRegister(registerMap.vL3()) && hasRegister(registerMap.iL3())) {
            double v3 = readSafe(words, registerMap.vL3());
            double i3 = readSafe(words, registerMap.iL3());
            double addI3 = (perPhaseKw * 1000.0) / Math.max(100.0, v3 * pf);
            i3 += addI3;
            writeIfPresent(words, registerMap.iL3(), (float) i3);
        }

        // bump total active power (W)
        double pTot = readSafe(words, registerMap.pTotal());
        pTot += (compensateKw * 1000.0);
        writeIfPresent(words, registerMap.pTotal(), (float) pTot);

        // optionally bump per-phase power (if mapped)
        bumpPerPhasePower(words, (float) perPhaseKw);

        log.debug("compensation_applied totalAddKw={} perPhaseKw={} pf={}", compensateKw, perPhaseKw, pf);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void bumpPerPhasePower(short[] words, float perPhaseKw) {
        float addW = perPhaseKw * 1000f;
        if (hasRegister(registerMap.pL1())) writeIfPresent(words, registerMap.pL1(), readSafe(words, registerMap.pL1()) + addW);
        if (hasRegister(registerMap.pL2())) writeIfPresent(words, registerMap.pL2(), readSafe(words, registerMap.pL2()) + addW);
        if (hasRegister(registerMap.pL3())) writeIfPresent(words, registerMap.pL3(), readSafe(words, registerMap.pL3()) + addW);
    }

    /** Safe float read via codec; returns 0 if the register is missing or out of range. */
    private float readSafe(short[] words, int wordOffset) {
        if (!hasRegister(wordOffset)) return 0f;
        return codec.readFloatOrDefault(words, wordOffset, 0f);
    }

    /** Safe float write: no-op if the register is missing or out of range. */
    private void writeIfPresent(short[] words, int wordOffset, float value) {
        if (!hasRegister(wordOffset)) return;
        int last = wordOffset + 1;
        if (last >= words.length) return;
        codec.writeFloat(words, wordOffset, value);
    }

    /** Treat negative offsets (e.g., -1) as “not mapped”. */
    private boolean hasRegister(int wordOffset) {
        return wordOffset >= 0;
    }

    /**
     * Linear ramp towards target over rampMs.
     * Assumes caller is invoked roughly once per second.
     */
    private float rampTowards(float current, float target, long rampMs) {
        if (rampMs <= 0) return target;
        float perSec = 1.0f / Math.max(1f, (rampMs / 1000f));
        float delta = target - current;
        float step = Math.abs(delta) * perSec;
        return current + Math.copySign(Math.min(Math.abs(delta), step), delta);
    }
}
