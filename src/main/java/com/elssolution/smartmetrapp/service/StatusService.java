package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.MeterDecoder;
import com.elssolution.smartmetrapp.domain.MeterRegisterMap;
import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.elssolution.smartmetrapp.integration.modbus.ModbusInverterFeeder;
import com.elssolution.smartmetrapp.integration.modbus.ModbusSmReader;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.text.DecimalFormat;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class StatusService {

    private static final DecimalFormat DF0 = new DecimalFormat("#0");
    private static final DecimalFormat DF1 = new DecimalFormat("#0.0");
    private static final DecimalFormat DF2 = new DecimalFormat("#0.00");
    private static final DecimalFormat DF3 = new DecimalFormat("#0.000");

    private final ScheduledExecutorService scheduler;
    private final ModbusSmReader smReader;
    private final LoadOverrideService loadOverride;
    private final MeterDecoder codec;
    private final MeterRegisterMap map;
    private final ModbusInverterFeeder feeder;

    // How often the human-friendly summary INFO line is printed
    private final int summaryEverySec = 30;

    public StatusService(ScheduledExecutorService scheduler,
                         ModbusSmReader smReader,
                         LoadOverrideService loadOverride,
                         MeterDecoder codec,
                         MeterRegisterMap map,
                         ModbusInverterFeeder feeder) {
        this.scheduler    = scheduler;
        this.smReader     = smReader;
        this.loadOverride = loadOverride;
        this.codec        = codec;
        this.map          = map;
        this.feeder       = feeder;
    }

    @PostConstruct
    void startSummaryLogger() {
        scheduler.scheduleAtFixedRate(this::logSummarySafe, 10, summaryEverySec, TimeUnit.SECONDS);
        log.info("Status summary logger started: every {}s", summaryEverySec);
    }

    // ---------------------- Public API ----------------------

    /** Used by the controller and by our periodic logger. */
    public StatusView buildStatusView() {
        boolean overrideOn = loadOverride.isOverrideEnabled();

        long now = System.currentTimeMillis();

        // --- Smart meter snapshot (raw from meter) ---
        SmSnapshot sm = smReader.getLatestSnapshotSM();
        long smAgeMs = (sm == null || sm.updatedAtMs == 0) ? -1 : (now - sm.updatedAtMs);
        PhaseBlock smPh = readPhasesFromWords(sm != null ? sm.data : null);
        float smPTotalW = readFloatOrZero(sm != null ? sm.data : null, map.pTotal());
        // (If per-phase active powers exist in your map, we could also read those into smPh.p1/p2/p3)

        // --- Output data snapshot (what we wrote for the inverter to read) ---
        short[] out = feeder.getOutputData();              // may be null until the first write
        long lastWrite = feeder.getLastWriteMs();
        long outAgeMs = (lastWrite == 0L) ? -1 : Math.max(0, now - lastWrite);
        PhaseBlock outPh = readPhasesFromWords(out);
        float outPTotalW = readFloatOrZero(out, map.pTotal());

        // --- Solis (grid) figures ---
        // currentDeltaKw is what we actually use to compensate
        double usedCompensateKw = overrideOn ? loadOverride.getCurrentDeltaKw() : 0.0;

        double lastPsumKw     = loadOverride.getPsumKw();
        double importFromGrid = 0.0;
        if (overrideOn) {
            if (!Double.isNaN(lastPsumKw) && lastPsumKw < 0) {
                importFromGrid = Math.abs(lastPsumKw);
            } else {
                importFromGrid = 0.0;
            }
        } else {
            importFromGrid = 0.0; // OFF mode requirement
        }

        double pvKw   = loadOverride.getLastDcPacKw();     // prefer DC PV power
        if (Double.isNaN(pvKw)) pvKw = loadOverride.getLastPacKw(); // fallback to AC

        double loadKw = loadOverride.getLastFamilyLoadKw();
        Integer solisState = loadOverride.getLastState();
        Integer warnInfo   = loadOverride.getLastWarningInfo();
        boolean alarm = (warnInfo != null && warnInfo != 0) || (solisState != null && solisState == 3);

        long solisAgeMs = (loadOverride.getLastUpdateMs() == 0L)
                ? -1
                : (now - loadOverride.getLastUpdateMs());

        return StatusView.builder()
                // Grid/Solis
                .gridImportKw(round3(importFromGrid))
                .gridRawPsumKw(round3(lastPsumKw))
                .minImportKw(round3(loadOverride.getMinImportKw()))
                .compensationKw(round3(usedCompensateKw))
                .gridAgeMs(solisAgeMs)

                // ON/OFF mode
                .overrideEnabled(overrideOn)
                .mode(overrideOn ? "NORMAL" : "PASS-THRU")

                // Smart meter now (decoded)
                .smV1(round1(smPh.v1))
                .smI1(round2(smPh.i1))
                .smV2(round1(smPh.v2))
                .smI2(round2(smPh.i2))
                .smV3(round1(smPh.v3))
                .smI3(round2(smPh.i3))
                .smPTotalW(Math.round(smPTotalW))
                .smAgeMs(smAgeMs)

                // Last output we produced (decoded)
                .outI1(round2(outPh.i1))
                .outI2(round2(outPh.i2))
                .outI3(round2(outPh.i3))
                .outPTotalW(Math.round(outPTotalW))
                .outAgeMs(outAgeMs)

                // Convenience human strings (for UI)
                .gridAgeHuman(humanAge(solisAgeMs))
                .smAgeHuman(humanAge(smAgeMs))
                .outAgeHuman(humanAge(outAgeMs))

                // Solis Inverter additional info
                .pvPowerKw(Double.isNaN(pvKw) ? Double.NaN : round3(pvKw))
                .loadPowerKw(Double.isNaN(loadKw) ? Double.NaN : round3(loadKw))
                .solisState(stateHuman(solisState))
                .alarm(alarm)

                .build();
    }

    // ---------------------- Periodic summary log ----------------------

    private void logSummarySafe() {
        try {
            StatusView v = buildStatusView();
            log.info(
                    "Status: gridImport={}kW (psum={}kW, minImport={}kW) → compensate={}kW; " +
                            "SM: V1={}V I1={}A, V2={}V I2={}A, V3={}V I3={}A, Ptot={}W (age {}); " +
                            "Out: I1={}A I2={}A I3={}A, Ptot={}W (age {})",
                    fmt(v.gridImportKw), fmt(v.gridRawPsumKw), fmt(v.minImportKw), fmt(v.compensationKw),
                    fmt(v.smV1), fmt(v.smI1), fmt(v.smV2), fmt(v.smI2), fmt(v.smV3), fmt(v.smI3), v.smPTotalW, v.smAgeHuman,
                    fmt(v.outI1), fmt(v.outI2), fmt(v.outI3), v.outPTotalW, v.outAgeHuman
            );
        } catch (Exception e) {
            log.warn("status_summary_failed: {}", e.getMessage());
        }
    }

    // ---------------------- Helpers ----------------------

    private PhaseBlock readPhasesFromWords(short[] words) {
        // if an offset is -1 (not mapped), we return 0 for that value
        float v1 = readFloatOrZero(words, map.vL1());
        float v2 = readFloatOrZero(words, map.vL2());
        float v3 = readFloatOrZero(words, map.vL3());
        float i1 = readFloatOrZero(words, map.iL1());
        float i2 = readFloatOrZero(words, map.iL2());
        float i3 = readFloatOrZero(words, map.iL3());
        return new PhaseBlock(v1, v2, v3, i1, i2, i3);
    }

    private float readFloatOrZero(short[] words, int off) {
        if (off < 0) return 0f; // “not mapped”
        return codec.readFloatOrDefault(words, off, 0f);
    }

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

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "-";
        return DF2.format(v);
    }

    // simple holder for three-phase V/I; (add P1..P3 later when you map them)
    private record PhaseBlock(float v1, float v2, float v3, float i1, float i2, float i3) {}

    @Builder
    @Value
    public static class StatusView {
        // Grid / Solis
        double gridImportKw;   // positive when importing from grid
        double gridRawPsumKw;  // raw psum from Solis (+ export, - import) if available
        double minImportKw;
        double compensationKw; // what we actually apply to the inverter
        long   gridAgeMs;
        String gridAgeHuman;

        // Smart meter snapshot
        float  smV1; float smI1;
        float  smV2; float smI2;
        float  smV3; float smI3;
        int    smPTotalW;
        long   smAgeMs;
        String smAgeHuman;

        // Last output we produced
        float  outI1; float outI2; float outI3;
        int    outPTotalW;
        long   outAgeMs;
        String outAgeHuman;

        // ON/OFF mode
        boolean overrideEnabled;
        String  mode;

        // Solis Inverter additional info
        double pvPowerKw;     // PV generation (kW)
        double loadPowerKw;   // site load (kW)
        String solisState;    // ONLINE/OFFLINE/ALARM/-
        boolean alarm;        // true if alarm bit or state==ALARM
    }
}