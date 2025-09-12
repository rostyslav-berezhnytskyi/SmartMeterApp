package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.elssolution.smartmetrapp.integration.modbus.ModbusInverterFeeder;
import com.elssolution.smartmetrapp.integration.modbus.ModbusSmReader;
import jakarta.annotation.PostConstruct;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Status aggregation (Acrel-only).
 * - Decodes Acrel voltages/currents/power from the meter snapshot and from the last output frame.
 * - Merges Solis/grid-side numbers from LoadOverrideService for UI/health.
 */
@Slf4j
@Component
public class StatusService {

    private static final DecimalFormat DF2 = new DecimalFormat("#0.00");

    private final ScheduledExecutorService scheduler;
    private final ModbusSmReader smReader;
    private final LoadOverrideService loadOverride;
    private final ModbusInverterFeeder feeder;

    public StatusService(ScheduledExecutorService scheduler,
                         ModbusSmReader smReader,
                         LoadOverrideService loadOverride,
                         ModbusInverterFeeder feeder) {
        this.scheduler = scheduler;
        this.smReader = smReader;
        this.loadOverride = loadOverride;
        this.feeder = feeder;
    }

    // Acrel scaling
    @Value("${smartmetr.scale.pt:1.0}") private double pt;
    @Value("${smartmetr.scale.ct:1.0}") private double ct;

    // Summary log period
    private final int summaryEverySec = 30;

    @PostConstruct
    void startSummaryLogger() {
        scheduler.scheduleAtFixedRate(this::logSummarySafe, 10, summaryEverySec, TimeUnit.SECONDS);
        log.info("Status summary logger started: every {}s", summaryEverySec);
    }

    // ---------------------- Public API ----------------------

    /** Used by controllers and the periodic logger. */
    public StatusView buildStatusView() {
        long now = System.currentTimeMillis();
        boolean overrideOn = loadOverride.isOverrideEnabled();

        // --- Smart meter snapshot (Acrel raw) ---
        SmSnapshot sm = smReader.getLatestSnapshotSM();
        long smAgeMs = (sm == null || sm.updatedAtMs == 0) ? -1 : (now - sm.updatedAtMs);
        PhaseBlock smPh = readAcrelPhases(sm != null ? sm.data : null);
        int smPTotalW = (int) Math.round(readAcrelPTotalW(sm != null ? sm.data : null));

        // --- Output image (what inverter reads) ---
        short[] out = feeder.getOutputData();
        long lastWrite = feeder.getLastWriteMs();
        long outAgeMs = (lastWrite == 0L) ? -1 : Math.max(0, now - lastWrite);
        PhaseBlock outPh = readAcrelPhases(out);
        int outPTotalW = (int) Math.round(readAcrelPTotalW(out));

        // --- Solis/grid figures ---
        double usedCompensateKw = overrideOn ? loadOverride.getCurrentDeltaKw() : 0.0;
        double lastPsumKw = loadOverride.getLastPsumKw(); // +export / -import
        double importFromGrid = (!Double.isNaN(lastPsumKw) && lastPsumKw < 0) ? Math.abs(lastPsumKw) : 0.0;
        double pvKw = loadOverride.getLastDcPacKw();
        if (Double.isNaN(pvKw)) pvKw = loadOverride.getLastPacKw();
        double loadKw = loadOverride.getLastFamilyLoadKw();
        Integer solisState = loadOverride.getLastState();
        Integer warnInfo   = loadOverride.getLastWarningInfo();
        boolean alarm = (warnInfo != null && warnInfo != 0) || (solisState != null && solisState == 3);
        long solisAgeMs = (loadOverride.getLastUpdateMs() == 0L) ? -1 : (now - loadOverride.getLastUpdateMs());

        return StatusView.builder()
                // Grid/Solis
                .gridImportKw(round3(importFromGrid))
                .gridRawPsumKw(round3(lastPsumKw))
                .minImportKw(round3(loadOverride.getMinImportKw()))
                .compensationKw(round3(usedCompensateKw))
                .gridAgeMs(solisAgeMs)
                .gridAgeHuman(humanAge(solisAgeMs))

                // ON/OFF mode
                .overrideEnabled(overrideOn)
                .mode(overrideOn ? "NORMAL" : "PASS-THRU")

                // Smart meter (decoded)
                .smV1(round1(smPh.v1)).smI1(round2(smPh.i1))
                .smV2(round1(smPh.v2)).smI2(round2(smPh.i2))
                .smV3(round1(smPh.v3)).smI3(round2(smPh.i3))
                .smPTotalW(smPTotalW)
                .smAgeMs(smAgeMs)
                .smAgeHuman(humanAge(smAgeMs))

                // Output (decoded)
                .outI1(round2(outPh.i1))
                .outI2(round2(outPh.i2))
                .outI3(round2(outPh.i3))
                .outPTotalW(outPTotalW)
                .outAgeMs(outAgeMs)
                .outAgeHuman(humanAge(outAgeMs))

                // Solis extras
                .pvPowerKw(Double.isNaN(pvKw) ? Double.NaN : round3(pvKw))
                .loadPowerKw(Double.isNaN(loadKw) ? Double.NaN : round3(loadKw))
                .solisState(stateHuman(solisState))
                .alarm(alarm)
                .build();
    }

    // ---------------------- Log summary ----------------------

    private void logSummarySafe() {
        try {
            StatusView v = buildStatusView();
            log.info(
                    "Status: gridImport={}kW (psum={}kW, minImport={}kW) → compensate={}kW; " +
                            "SM: V1={}V I1={}A, V2={}V I2={}A, V3={}V I3={}A, Ptot={}W (age {}); " +
                            "Out: I1={}A I2={}A I3={}A, Ptot={}W (age {})",
                    fmt(v.gridImportKw), fmt(v.gridRawPsumKw), fmt(v.minImportKw), fmt(v.compensationKw),
                    fmt(v.smV1), fmt(v.smI1), fmt(v.smV2), fmt(v.smI2), fmt(v.smV3), fmt(v.smI3),
                    v.smPTotalW, v.smAgeHuman,
                    fmt(v.outI1), fmt(v.outI2), fmt(v.outI3), v.outPTotalW, v.outAgeHuman
            );
        } catch (Exception e) {
            log.warn("status_summary_failed: {}", e.getMessage());
        }
    }

    // ---------------------- Acrel decode helpers ----------------------

    private PhaseBlock readAcrelPhases(short[] w) {
        if (w == null) return new PhaseBlock(0,0,0,0,0,0);
        double v1 = 0.1 * u16(w, 97) * pt;
        double v2 = 0.1 * u16(w, 98) * pt;
        double v3 = 0.1 * u16(w, 99) * pt;
        double i1 = 0.01 * u16(w, 100) * ct;
        double i2 = 0.01 * u16(w, 101) * ct;
        double i3 = 0.01 * u16(w, 102) * ct;
        return new PhaseBlock((float)v1,(float)v2,(float)v3,(float)i1,(float)i2,(float)i3);
    }

    private double readAcrelPTotalW(short[] w) {
        if (w == null) return 0.0;
        int raw = i32be(w, 362); // raw=W/(PT*CT)
        return raw * pt * ct;
    }

    // raw utils
    private static int u16(short[] a, int idx) {
        if (a == null || idx < 0 || idx >= a.length) return 0;
        return a[idx] & 0xFFFF;
    }
    private static int i32be(short[] a, int msw) {
        if (a == null || msw < 0 || msw + 1 >= a.length) return 0;
        int hi = u16(a, msw);
        int lo = u16(a, msw + 1);
        return (hi << 16) | lo;
    }

    // ---------------------- formatting helpers ----------------------

    private static String humanAge(long ageMs) {
        if (ageMs < 0) return "-";
        if (ageMs < 1000) return ageMs + " ms";
        long s = ageMs / 1000;
        if (s < 60) return s + " s";
        long m = s / 60;
        long remS = s % 60;
        return m + " min " + remS + " s";
    }

    private static String stateHuman(Integer s) {
        if (s == null) return "-";
        return switch (s) {
            case 1 -> "ONLINE";
            case 2 -> "OFFLINE";
            case 3 -> "ALARM";
            default -> String.valueOf(s);
        };
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static float  round2(float  v) { return Math.round(v * 100f)   / 100f; }
    private static float  round1(float  v) { return Math.round(v * 10f)    / 10f;  }
    private static String fmt(double v)    { return Double.isNaN(v) ? "-" : DF2.format(v); }

    private record PhaseBlock(float v1, float v2, float v3, float i1, float i2, float i3) {}

    @Builder @Getter @ToString @EqualsAndHashCode @AllArgsConstructor
    public static class StatusView {
        // Grid / Solis
        double gridImportKw;
        double gridRawPsumKw;
        double minImportKw;
        double compensationKw;
        long   gridAgeMs;
        String gridAgeHuman;

        // Smart meter
        float  smV1; float smI1;
        float  smV2; float smI2;
        float  smV3; float smI3;
        int    smPTotalW;
        long   smAgeMs;
        String smAgeHuman;

        // Output
        float  outI1; float outI2; float outI3;
        int    outPTotalW;
        long   outAgeMs;
        String outAgeHuman;

        // Mode
        boolean overrideEnabled;
        String  mode;

        // Solis extras
        double pvPowerKw;
        double loadPowerKw;
        String solisState;
        boolean alarm;
    }
}
