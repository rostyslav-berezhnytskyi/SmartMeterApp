package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.alerts.AlertService;
import com.elssolution.smartmetrapp.integration.solis.SolisCloudClient;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Computes the desired compensation (kW) from Solis API data.
 *
 * Rules:
 * - If override is DISABLED => returns 0.0 (so PowerControlService does pure pass-through).
 * - If API data is too old => returns 0.0.
 * - Otherwise:
 *     importKw = max(0, -psumKw)   // psum<0 means importing
 *     target   = (importKw > minImportKw) ? importKw : 0
 *     currentDeltaKw = EMA(previous, target, smoothingFactor) + slew limiting + clamps
 */
@Slf4j
@Component
@Getter @Setter
public class LoadOverrideService {

    private final SolisCloudClient solis;
    private final ScheduledExecutorService scheduler;
    private final AlertService alerts;

    // === Config ===
    @Value("${solis.overrideEnabled:true}")
    private boolean overrideEnabled;

    /** How often we call the Solis API (seconds). */
    @Value("${solis.fetch.periodSeconds:10}")
    private int fetchPeriodSeconds;

    /** Minimum import from grid (kW) before we start compensating. */
    @Value("${solis.minImportKw:0.2}")
    private double minImportKw;

    /** If no fresh API response within this time, treat delta as 0 (ms). */
    @Value("${solis.maxDataAgeMs:300000}")
    private long maxDataAgeMs;

    /** EMA smoothing (0..1). 1.0 = immediate, 0 = disabled (use target directly). */
    @Value("${solis.smoothingFactor:0.8}")
    private double smoothingFactor;

    /** Hard cap for requested compensation (kW). */
    @Value("${smartmetr.clamp.maxKw:50}")
    private double clampMaxKw;

    /** Max rate-of-change (kW per second) for the commanded delta. */
    @Value("${smartmetr.deltaMaxKwPerSec:2}")
    private double deltaMaxKwPerSec;

    // === State exposed to others ===
    private volatile double currentDeltaKw = 0.0;  // smoothed & slew-limited target
    private volatile long   lastUpdateMs   = 0L;   // last time we updated from API

    // Cached values for UI/StatusService
    private volatile double  lastPsumKw       = Double.NaN; // +export / -import
    private volatile double  lastPacKw        = Double.NaN; // AC power
    private volatile double  lastDcPacKw      = Double.NaN; // DC power (preferred for PV)
    private volatile double  lastFamilyLoadKw = Double.NaN; // site load
    private volatile Integer lastState        = null;       // ONLINE/OFFLINE/ALARM codes
    private volatile Integer lastWarningInfo  = null;

    public LoadOverrideService(SolisCloudClient solis, ScheduledExecutorService scheduler, AlertService alerts) {
        this.solis = solis;
        this.scheduler = scheduler;
        this.alerts = alerts;
    }

    @PostConstruct
    void startPolling() {
        // sanitize knobs
        if (minImportKw < 0) {
            log.warn("minImportKw < 0 ({}). Clamping to 0.", minImportKw);
            minImportKw = 0;
        }
        if (smoothingFactor < 0 || smoothingFactor > 1) {
            log.warn("smoothingFactor out of [0..1] ({}). Using 1.0 (no smoothing).", smoothingFactor);
            smoothingFactor = 1.0;
        }
        if (maxDataAgeMs < 5_000) {
            log.warn("maxDataAgeMs too small ({}). Bumping to 5000 ms.", maxDataAgeMs);
            maxDataAgeMs = 5_000;
        }
        if (clampMaxKw < 0) {
            log.warn("clampMaxKw < 0 ({}). Clamping to 0.", clampMaxKw);
            clampMaxKw = 0;
        }
        if (deltaMaxKwPerSec < 0) {
            log.warn("deltaMaxKwPerSec < 0 ({}). Clamping to 0.", deltaMaxKwPerSec);
            deltaMaxKwPerSec = 0;
        }

        scheduler.scheduleWithFixedDelay(this::pollOnceSafe, 5, fetchPeriodSeconds, TimeUnit.SECONDS);
        log.info("Solis override polling: every={}s, minImportKw={} kW, maxDataAgeMs={}, smoothingFactor={}, clampMaxKw={}, deltaMaxKwPerSec={}",
                fetchPeriodSeconds, minImportKw, maxDataAgeMs, smoothingFactor, clampMaxKw, deltaMaxKwPerSec);
    }

    /** One safe polling cycle: fetch psum from Solis and update currentDeltaKw. */
    private void pollOnceSafe() {
        try {
            Optional<SolisCloudClient.SolisDetail> opt = solis.fetchInverterDetailRich();
            if (opt.isEmpty()) {
                decayToZeroIfTooOld();
                return;
            }

            alerts.resolve("SOLIS_STALE");
            SolisCloudClient.SolisDetail r = opt.get();

            // Cache values for UI
            lastPsumKw       = r.psumKw();
            lastPacKw        = (r.pacKw()       != null) ? r.pacKw()       : Double.NaN;
            lastDcPacKw      = (r.dcPacKw()     != null) ? r.dcPacKw()     : Double.NaN;
            lastFamilyLoadKw = (r.familyLoadKw()!= null) ? r.familyLoadKw(): Double.NaN;
            lastState        = r.state();
            lastWarningInfo  = r.warningInfoData();

            // ---- SAFETY GATE: pause override if inverter not ONLINE or has warnings
            boolean okState = (r.state() == null) || (r.state() == 1);
            boolean okWarn  = (r.warningInfoData() == null) || (r.warningInfoData() == 0);
            if (!okState || !okWarn) {
                alerts.raise("SOLIS_ALARM",
                        "Override paused: state=" + r.state() + " warn=" + r.warningInfoData(),
                        AlertService.Severity.WARN);
                currentDeltaKw = 0.0;
                lastUpdateMs   = System.currentTimeMillis();
                return;
            } else {
                alerts.resolve("SOLIS_ALARM");
            }

            // ---- Target from psum (import only), apply deadband
            double importKw = (lastPsumKw < 0) ? Math.abs(lastPsumKw) : 0.0;
            double target   = (importKw > minImportKw) ? importKw : 0.0;

            // Hard clamp
            target = Math.min(target, clampMaxKw);

            // EMA smoothing
            double rawSmoothed = ema(currentDeltaKw, target, smoothingFactor);

            // Slew-limit per polling step
            double seconds = Math.max(1.0, (double) fetchPeriodSeconds);
            double stepMax = Math.max(0.0, deltaMaxKwPerSec) * seconds;
            double diff    = rawSmoothed - currentDeltaKw;
            if (diff >  stepMax) diff =  stepMax;
            if (diff < -stepMax) diff = -stepMax;

            currentDeltaKw = currentDeltaKw + diff;
            // final clamp & non-negative
            if (currentDeltaKw < 0.0) currentDeltaKw = 0.0;
            if (currentDeltaKw > clampMaxKw) currentDeltaKw = clampMaxKw;

            lastUpdateMs = System.currentTimeMillis();

            if (log.isDebugEnabled()) {
                log.debug("Solis update: psumKw={} → import={} → target={} → ema={} → stepMax={} → delta={}",
                        lastPsumKw, importKw, target, rawSmoothed, stepMax, currentDeltaKw);
            }

        } catch (Exception e) {
            log.warn("Solis polling failed: {}", e.getMessage());
            decayToZeroIfTooOld();
        }
    }

    /** EMA clamp-and-apply. If smoothing ∉ (0,1), returns target directly. */
    private static double ema(double prev, double target, double smoothing) {
        if (!Double.isFinite(target)) return 0.0;
        if (!Double.isFinite(prev))   prev = 0.0;
        if (smoothing <= 0.0 || smoothing >= 1.0) return target;
        return smoothing * target + (1.0 - smoothing) * prev;
    }

    /** If last update is too old, decay to zero and raise a warning once. */
    private void decayToZeroIfTooOld() {
        long age = System.currentTimeMillis() - lastUpdateMs;
        if (lastUpdateMs > 0 && age > maxDataAgeMs && currentDeltaKw != 0.0) {
            alerts.raise("SOLIS_STALE", "No fresh Solis data for " + age + " ms", AlertService.Severity.WARN);
            currentDeltaKw = 0.0;
        }
    }

    /**
     * Value used by PowerControlService.
     * - If override is OFF → 0.0 (pure pass-through).
     * - If data stale       → 0.0
     * - Else                → current smoothed delta (clamped)
     */
    public double getCurrentDeltaKw() {
        if (!overrideEnabled) return 0.0;
        long age = System.currentTimeMillis() - lastUpdateMs;
        if (lastUpdateMs == 0L || age > maxDataAgeMs) return 0.0;
        // final guard
        double v = currentDeltaKw;
        if (!Double.isFinite(v) || v < 0.0) return 0.0;
        if (v > clampMaxKw) return clampMaxKw;
        return v;
    }
}
