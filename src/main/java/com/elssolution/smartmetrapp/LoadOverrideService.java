package com.elssolution.smartmetrapp;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Component
public class LoadOverrideService {

    private final SolisCloudClientService solis;
    private final ScheduledExecutorService scheduler;

    // === Config (from application.yml / .env) ===
    /** How often we call the Solis API, in seconds. */
    @Value("${solis.fetch.periodSeconds:10}")
    private int fetchPeriodSeconds;

    /** Minimum import from grid (kW) before we start compensating. */
    @Value("${solis.minImportKw:1.0}")
    private double minImportKw;

    /** If we don't get a fresh API response within this time, treat delta as 0. */
    @Value("${solis.maxDataAgeMs:180000}") // 3 minutes
    private long maxDataAgeMs;

    /**
     * Smoothing factor for exponential moving average (0..1).
     * 1.0 = no smoothing (use target immediately).
     * 0.2..0.8 = gradual changes.
     * <=0 disables smoothing (use target).
     */
    @Value("${solis.smoothingFactor:1.0}")
    private double smoothingFactor;

    // === State (volatile for cross-thread reads) ===
    private volatile double currentDeltaKw = 0.0;
    // ==================================================================

    private volatile long lastUpdateMs = 0L;

    public LoadOverrideService(SolisCloudClientService solis, ScheduledExecutorService scheduler) {
        this.solis = solis;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void startPolling() {
        scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 5, fetchPeriodSeconds, TimeUnit.SECONDS);
        log.info(
                "Solis override polling started: every={}s, minImportKw={} kW, maxDataAgeMs={}, smoothingFactor={}",
                fetchPeriodSeconds, minImportKw, maxDataAgeMs, smoothingFactor
        );
    }

    /** One safe polling cycle: fetch raw psum from Solis and update delta. */
    private void pollOnceSafe() {
        try {
            Optional<SolisCloudClientService.SolisReading> opt = solis.fetchInverterDetail();
            if (opt.isEmpty()) {
                decayToZeroIfTooOld();
                return;
            }

            SolisCloudClientService.SolisReading reading = opt.get();
            double psumKw = reading.psumKw(); // + export, - import

            // Convert psum to "importKw": positive when importing from grid, else 0
            double importKw;
            if (psumKw < 0) {
                importKw = Math.abs(psumKw);
            } else {
                importKw = 0.0;
            }

            // Apply the "do nothing if small" rule
            double targetDeltaKw;
            if (importKw > minImportKw) {
                targetDeltaKw = importKw;
            } else {
                targetDeltaKw = 0.0;
            }

            // Smooth (optional EMA)
            double newDelta = applySmoothing(currentDeltaKw, targetDeltaKw, smoothingFactor);

            currentDeltaKw = newDelta;
            lastUpdateMs = System.currentTimeMillis();


            log.debug("Solis update: psumKw={} → importKw={} → targetDeltaKw={} → smoothedDeltaKw={}",
                    psumKw, importKw, targetDeltaKw, newDelta);

        } catch (Exception e) {
            log.warn("Solis polling failed: {}", e.getMessage());
            decayToZeroIfTooOld();
        }
    }

    /** Simple EMA; if smoothingFactor not in (0,1), just return target. */
    private double applySmoothing(double previous, double target, double smoothing) {
        if (smoothing <= 0.0 || smoothing >= 1.0) {
            return target; // disabled or "no smoothing"
        }
        return (smoothing * target) + ((1.0 - smoothing) * previous);
    }

    /** If last successful update is too old, decay delta to zero exactly once. */
    private void decayToZeroIfTooOld() {
        long age = System.currentTimeMillis() - lastUpdateMs;
        if (lastUpdateMs > 0 && age > maxDataAgeMs && currentDeltaKw != 0.0) {
            currentDeltaKw = 0.0;
            log.warn("No fresh Solis data for {} ms → override set to 0 kW", age);
        }
    }

    /**
     * Called by the feeder. Returns the currently valid delta (kW).
     * If the data is too old, returns 0.
     */
    public double getCurrentDeltaKw() {
        long age = System.currentTimeMillis() - lastUpdateMs;
        if (lastUpdateMs == 0L || age > maxDataAgeMs) {
            return 0.0;
        }
        return currentDeltaKw;
    }
}