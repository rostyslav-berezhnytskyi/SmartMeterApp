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
    private final MeterMap registerMap; // clearer than 'map'

    public PowerController(MeterCodec codec, MeterMap registerMap) {
        this.codec = codec;
        this.registerMap = registerMap;
    }

    /** If we don't get a recent snapshot within this time, we send zeros for I/P */
    @Value("${smartmetr.maxDataAgeMs:300000}") // 5 minutes default
    private long maxDataAgeMs;

    /** Ramp time to go to zero on “too old” data (0 = immediate jump). */
    @Value("${smartmetr.rampToZeroMs:2000}")
    private long rampToZeroMs;

    /** Minimum power factor to use when converting kW to A (safety clamp). */
    @Value("${smartmetr.minPowerFactor:0.95}")
    private double minPowerFactor;

    /**
     * Build the words to publish to the inverter.
     * @param snapshot   last good meter read (raw words + timestamp)
     * @param compensateKw how many kW we want to "absorb" (>= 0)
     * @return output words for the inverter (same layout as snapshot)
     */
    public short[] prepareOutputWords(SmSnapshot snapshot, double compensateKw) {
        // start from the last snapshot; clone so we never mutate shared arrays
        short[] outWords = (snapshot != null && snapshot.data != null)
                ? snapshot.data.clone()
                : new short[72];

        if (!isRecent(snapshot, maxDataAgeMs)) {
            long age = (snapshot == null) ? -1L : (System.currentTimeMillis() - snapshot.updatedAtMs);
            log.warn("meter_data_too_old ageMs={} → driving currents & powers to 0", age);
            driveToNeutral(outWords); // zero I*/P* with optional ramp
            return outWords;
        }

        // fresh: apply compensation evenly across 3 phases
        if (compensateKw > 0.0) {
            applyCompensationThreePhase(outWords, compensateKw);
        }
        return outWords;
    }

    // ------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------

    private boolean isRecent(SmSnapshot s, long maxAgeMs) {
        if (s == null || s.updatedAtMs <= 0) return false;
        return (System.currentTimeMillis() - s.updatedAtMs) <= maxAgeMs;
    }

    /** Zero active power(s) & current(s); keep voltages (0 V can be treated as a fault by some devices). */
    private void driveToNeutral(short[] words) {
        // total power & L1 current always handled; L2/L3 if available
        float pTot = readSafe(words, registerMap.pTotal());
        float i1   = readSafe(words, registerMap.iL1());
        float i2   = readSafe(words, registerMap.iL2());
        float i3   = readSafe(words, registerMap.iL3());

        float pTarget = 0f, iTarget = 0f;

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

        // If you expose per-phase active power registers, zero them too:
        writeIfPresent(words, registerMap.pL1(), 0f);
        writeIfPresent(words, registerMap.pL2(), 0f);
        writeIfPresent(words, registerMap.pL3(), 0f);
    }

    /**
     * Split compensateKw evenly: 1/3 per phase. For each phase:
     *   addI = (perPhaseKw * 1000) / max(100, V_phase * powerFactor)
     * Then bump total active power by (compensateKw * 1000).
     */
    private void applyCompensationThreePhase(short[] words, double compensateKw) {
        final double pf = Math.max(0.80, minPowerFactor); // safety clamp
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

        // optionally bump per-phase power (if your inverter reads them)
        bumpPerPhasePower(words, (float) perPhaseKw);
        log.debug("compensation_applied totalAddKw={} perPhaseKw={}", compensateKw, perPhaseKw);
    }

    private void bumpPerPhasePower(short[] words, float perPhaseKw) {
        // If any of pL1/2/3 are missing (-1), we skip silently.
        float addW = perPhaseKw * 1000f;
        if (hasRegister(registerMap.pL1())) writeIfPresent(words, registerMap.pL1(), readSafe(words, registerMap.pL1()) + addW);
        if (hasRegister(registerMap.pL2())) writeIfPresent(words, registerMap.pL2(), readSafe(words, registerMap.pL2()) + addW);
        if (hasRegister(registerMap.pL3())) writeIfPresent(words, registerMap.pL3(), readSafe(words, registerMap.pL3()) + addW);
    }

    // ---------- low-level helpers ----------

    /** Safe float read: returns 0 if the register is missing or out of range. */
    private float readSafe(short[] words, int wordOffset) {
        if (!hasRegister(wordOffset)) return 0f;
        if (wordOffset + 1 >= words.length) return 0f;
        return codec.readFloat(words, wordOffset);
    }

    /** Safe float write: no-op if the register is missing or out of range. */
    private void writeIfPresent(short[] words, int wordOffset, float value) {
        if (!hasRegister(wordOffset)) return;
        if (wordOffset + 1 >= words.length) return;
        codec.writeFloat(words, wordOffset, value);
    }

    /** Treat negative offsets (e.g., -1) as “not mapped”. */
    private boolean hasRegister(int wordOffset) {
        return wordOffset >= 0;
    }

    /** Linear ramp towards target over rampMs (assuming ~1 Hz call rate). */
    private float rampTowards(float current, float target, long rampMs) {
        if (rampMs <= 0) return target;
        float perSec = 1.0f / Math.max(1f, (rampMs / 1000f));
        float delta = target - current;
        float step = Math.abs(delta) * perSec;
        return current + Math.copySign(Math.min(Math.abs(delta), step), delta);
    }
}
