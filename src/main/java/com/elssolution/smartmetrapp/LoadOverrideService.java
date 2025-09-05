package com.elssolution.smartmetrapp;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Component
@Getter @Setter
public class LoadOverrideService {

    private final SolisCloudClientService solis;
    private final ScheduledExecutorService scheduler;
    private final AlertService alerts;

    // === Config (from application.yml / .env) ===
    @Value("${solis.overrideEnabled:true}")
    private boolean overrideEnabled;  // NEW

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
    private volatile double psumKw;

    // store last values for UI (NaN/null until first read)
    private volatile double lastPsumKw = Double.NaN;   // you already had psumKw; keep both if you like
    private volatile double lastPacKw  = Double.NaN;
    private volatile double lastDcPacKw = Double.NaN;
    private volatile double lastFamilyLoadKw = Double.NaN;
    private volatile Integer lastState = null;
    private volatile Integer lastWarningInfo = null;

    private Double[] solisOutputData = new Double[4];

    public LoadOverrideService(SolisCloudClientService solis, ScheduledExecutorService scheduler, AlertService alerts) {
        this.solis = solis;
        this.scheduler = scheduler;
        this.alerts = alerts;
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
            Optional<SolisCloudClientService.SolisDetail> opt = solis.fetchInverterDetailRich();
            if (opt.isEmpty()) {
                decayToZeroIfTooOld();
                return;
            }
            alerts.resolve("SOLIS_STALE");
            var reading = opt.get();
            double psumKw = reading.psumKw(); // + export, - import
            this.psumKw = psumKw;
            this.lastPsumKw = psumKw;

            // Cache for UI
            this.lastPacKw = reading.pacKw() != null ? reading.pacKw() : Double.NaN;
            this.lastDcPacKw = reading.dcPacKw() != null ? reading.dcPacKw() : Double.NaN;
            this.lastFamilyLoadKw = reading.familyLoadKw() != null ? reading.familyLoadKw() : Double.NaN;
            this.lastState = reading.state();
            this.lastWarningInfo = reading.warningInfoData();

            // old logic → importKw from psum
            double importKw = (psumKw < 0) ? Math.abs(psumKw) : 0.0;
            double targetDeltaKw = (importKw > minImportKw) ? importKw : 0.0;

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
            alerts.raise("SOLIS_STALE", "No fresh Solis data for " + age + " ms", AlertService.Severity.WARN);
            if (currentDeltaKw != 0.0) {
                currentDeltaKw = 0.0;
            }
        }
    }

    /**
     * Called by the feeder. Returns the currently valid delta (kW).
     * If the data is too old, returns 0.
     */
    public double getCurrentDeltaKw() {
        if (!overrideEnabled) return 0.0;  // hard disable the effect of SolisAPI
        long age = System.currentTimeMillis() - lastUpdateMs;
        if (lastUpdateMs == 0L || age > maxDataAgeMs) {
            return 0.0;
        }
        return currentDeltaKw;
    }
}