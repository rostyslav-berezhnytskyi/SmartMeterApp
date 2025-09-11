package com.elssolution.smartmetrapp.service;

import com.elssolution.smartmetrapp.domain.MeterDecoder;
import com.elssolution.smartmetrapp.domain.MeterRegisterMap;
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

@Slf4j
@Component
public class StatusService {

    private static final DecimalFormat DF2 = new DecimalFormat("#0.00");

    private final ScheduledExecutorService scheduler;
    private final ModbusSmReader smReader;
    private final LoadOverrideService loadOverride;
    private final MeterDecoder codec;
    private final MeterRegisterMap map;
    private final ModbusInverterFeeder feeder;

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

    @Value("${smartmetr.kind:acrel}") private String meterKind;
    @Value("${smartmetr.scale.pt:1.0}") private double acrelPt;
    @Value("${smartmetr.scale.ct:1.0}") private double acrelCt;

    private final int summaryEverySec = 30;

    @PostConstruct
    void startSummaryLogger() {
        scheduler.scheduleAtFixedRate(this::logSummarySafe, 10, summaryEverySec, TimeUnit.SECONDS);
        log.info("Status summary logger started: every {}s", summaryEverySec);
    }

    public StatusView buildStatusView() {
        long now = System.currentTimeMillis();

        // --- Smart meter snapshot ---
        SmSnapshot sm = smReader.getLatestSnapshotSM();
        long smAgeMs = (sm == null || sm.updatedAtMs == 0) ? -1 : (now - sm.updatedAtMs);

        PhaseBlock smPh;
        float smPTotalW;
        if ("acrel".equalsIgnoreCase(meterKind)) {
            smPh = readPhasesFromAcrel(sm != null ? sm.data : null);
            smPTotalW = (float) (i32be(sm != null ? sm.data : null, 362) * acrelPt * acrelCt); // W
        } else {
            smPh = readPhasesFromEastron(sm != null ? sm.data : null);
            smPTotalW = readFloatOrZero(sm != null ? sm.data : null, map.pTotal());
        }

        // --- Out snapshot (what inverter reads) ---
        short[] out = feeder.getOutputData();
        long lastWrite = feeder.getLastWriteMs();
        long outAgeMs = (lastWrite == 0L) ? -1 : Math.max(0, now - lastWrite);

        PhaseBlock outPh;
        float outPTotalW;

        // robust detection instead of length check
        boolean outLooksAcrel = looksLikeAcrelFrame(out);

        if ("acrel".equalsIgnoreCase(meterKind) && outLooksAcrel) {
            outPh = readPhasesFromAcrel(out);
            int raw = i32be(out, 362);
            if (badRaw32(raw)) {
                // Fall back to your float-map pTotal if the raw slot is bogus
                outPTotalW = readFloatOrZero(out, map.pTotal());
            } else {
                // Acrel raw: raw is in W/(PT*CT) → multiply back to W
                outPTotalW = (float) (raw * acrelPt * acrelCt);
            }
        } else {
            // Not Acrel-shaped → decode as your Eastron-like float image
            outPh = readPhasesFromEastron(out);
            outPTotalW = readFloatOrZero(out, map.pTotal());
        }

        boolean overrideOn = loadOverride.isOverrideEnabled();
        double usedCompensateKw = overrideOn ? loadOverride.getCurrentDeltaKw() : 0.0;
        double lastPsumKw = loadOverride.getPsumKw();
        double importFromGrid = (!Double.isNaN(lastPsumKw) && lastPsumKw < 0) ? Math.abs(lastPsumKw) : 0.0;
        Integer solisState = loadOverride.getLastState(); // comes from Solis API

        return StatusView.builder()
                .gridImportKw(round3(importFromGrid))
                .gridRawPsumKw(round3(lastPsumKw))
                .minImportKw(round3(loadOverride.getMinImportKw()))
                .compensationKw(round3(usedCompensateKw))
                .gridAgeMs((loadOverride.getLastUpdateMs() == 0L) ? -1 : (now - loadOverride.getLastUpdateMs()))

                .overrideEnabled(overrideOn)
                .mode(overrideOn ? "NORMAL" : "PASS-THRU")

                .smV1(round1(smPh.v1)) .smI1(round2(smPh.i1))
                .smV2(round1(smPh.v2)) .smI2(round2(smPh.i2))
                .smV3(round1(smPh.v3)) .smI3(round2(smPh.i3))
                .smPTotalW(Math.round(smPTotalW))
                .smAgeMs(smAgeMs)

                .outI1(round2(outPh.i1))
                .outI2(round2(outPh.i2))
                .outI3(round2(outPh.i3))
                .outPTotalW(Math.round(outPTotalW))
                .outAgeMs(outAgeMs)
                .solisState(stateHuman(solisState))

                .build();
    }

    private void logSummarySafe() {
        try {
            StatusView v = buildStatusView();
            log.info(
                    "Status: gridImport={}kW (psum={}kW, minImport={}kW) → compensate={}kW; " +
                            "SM: V1={}V I1={}A, V2={}V I2={}A, V3={}V I3={}A, Ptot={}W (age {} ms); " +
                            "Out: I1={}A I2={}A I3={}A, Ptot={}W (age {} ms)",
                    fmt(v.gridImportKw), fmt(v.gridRawPsumKw), fmt(v.minImportKw), fmt(v.compensationKw),
                    fmt(v.smV1), fmt(v.smI1), fmt(v.smV2), fmt(v.smI2), fmt(v.smV3), fmt(v.smI3), v.smPTotalW, v.smAgeMs,
                    fmt(v.outI1), fmt(v.outI2), fmt(v.outI3), v.outPTotalW, v.outAgeMs
            );
        } catch (Exception e) {
            log.warn("status_summary_failed: {}", e.getMessage());
        }
    }

    // -------- helpers (Acrel decode) --------
    private PhaseBlock readPhasesFromAcrel(short[] w) {
        if (w == null) return new PhaseBlock(0,0,0,0,0,0);
        double v1 = 0.1 * u16(w, 97) * acrelPt;
        double v2 = 0.1 * u16(w, 98) * acrelPt;
        double v3 = 0.1 * u16(w, 99) * acrelPt;
        double i1 = 0.01 * u16(w, 100) * acrelCt;
        double i2 = 0.01 * u16(w, 101) * acrelCt;
        double i3 = 0.01 * u16(w, 102) * acrelCt;
        return new PhaseBlock((float)v1,(float)v2,(float)v3,(float)i1,(float)i2,(float)i3);
    }

    // -------- helpers (Eastron float) --------
    private PhaseBlock readPhasesFromEastron(short[] w) {
        float v1 = readFloatOrZero(w, map.vL1());
        float v2 = readFloatOrZero(w, map.vL2());
        float v3 = readFloatOrZero(w, map.vL3());
        float i1 = readFloatOrZero(w, map.iL1());
        float i2 = readFloatOrZero(w, map.iL2());
        float i3 = readFloatOrZero(w, map.iL3());
        return new PhaseBlock(v1,v2,v3,i1,i2,i3);
    }

    private float readFloatOrZero(short[] words, int off) {
        if (off < 0) return 0f;
        return codec.readFloatOrDefault(words, off, 0f);
    }

    // raw helpers
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

    private static String stateHuman(Integer s) {
        if (s == null) return "-";
        return switch (s) {
            case 1 -> "ONLINE";
            case 2 -> "OFFLINE";
            case 3 -> "ALARM";
            default -> String.valueOf(s);
        };
    }

    /** Heuristic: does the buffer look like Acrel raw? (V*10 at 97..99 and Hz*100 at 119) */
    private boolean looksLikeAcrelFrame(short[] w) {
        if (w == null || w.length <= 363) return false;
        int ua10 = u16(w, 97), ub10 = u16(w, 98), uc10 = u16(w, 99);   // ~2300
        int hz100 = u16(w, 119);                                       // ~5000 (for 50.00 Hz)
        boolean vOk = ua10 >= 1600 && ua10 <= 2800
                && ub10 >= 1600 && ub10 <= 2800
                && uc10 >= 1600 && uc10 <= 2800;
        boolean fOk = hz100 >= 4500 && hz100 <= 5500;
        return vOk && fOk;
    }

    /** Known bogus/sentinel i32 values we see when the slot isn't populated */
    private boolean badRaw32(int x) {
        return x == 0x7fffffff || x == 0x80000000;
    }

    private static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }
    private static float  round2(float  v) { return Math.round(v * 100f)   / 100f; }
    private static float  round1(float  v) { return Math.round(v * 10f)    / 10f;  }
    private static String fmt(double v) { return Double.isNaN(v) ? "-" : DF2.format(v); }

    private record PhaseBlock(float v1, float v2, float v3, float i1, float i2, float i3) {}

    @Builder
    @Getter
    @ToString
    @EqualsAndHashCode
    @AllArgsConstructor
    public static class StatusView {
        double gridImportKw;
        double gridRawPsumKw;
        double minImportKw;
        double compensationKw;
        long   gridAgeMs;

        boolean overrideEnabled;
        String  mode;

        float  smV1; float smI1;
        float  smV2; float smI2;
        float  smV3; float smI3;
        int    smPTotalW;
        long   smAgeMs;

        float  outI1; float outI2; float outI3;
        int    outPTotalW;
        long   outAgeMs;

        String solisState;
    }
}
