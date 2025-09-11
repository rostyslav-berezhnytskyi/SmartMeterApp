package com.elssolution.smartmetrapp.integration.modbus;

import com.elssolution.smartmetrapp.alerts.AlertService;
import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.elssolution.smartmetrapp.service.LoadOverrideService;
import com.elssolution.smartmetrapp.service.PowerControlService;
import com.serotonin.modbus4j.BasicProcessImage;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Getter @Setter
public class ModbusInverterFeeder {

    private final ScheduledExecutorService scheduler;
    private final ModbusSmReader smReader;
    private final LoadOverrideService loadOverride;
    private final PowerControlService powerControlService;
    private final AlertService alerts;

    public ModbusInverterFeeder(ScheduledExecutorService scheduler,
                                ModbusSmReader smReader,
                                LoadOverrideService loadOverride,
                                PowerControlService powerControlService,
                                AlertService alerts) {
        this.scheduler = scheduler;
        this.smReader = smReader;
        this.loadOverride = loadOverride;
        this.powerControlService = powerControlService;
        this.alerts = alerts;
    }

    @Value("${serial.output.slaveId}") private int slaveId;
    @Value("${serial.output.port}")    private String port;
    @Value("${serial.output.baudRate}")private int baudRate;

    /** We need to reach Acrel register 363 comfortably. */
    private static final int WRITE_REG_COUNT = 400;

    private final Object writeLock = new Object();
    private volatile BasicProcessImage processImage;
    private volatile ModbusSlaveSet   slaveSet;
    private volatile boolean isUp = false;

    private short[] outputData;
    private volatile long lastWriteMs = 0L;
    private volatile long lastBuildMs = 0L;

    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::ensureOpen, 0, 5, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pushOneCycle, 1, 1, TimeUnit.SECONDS);
    }

    private void ensureOpen() {
        if (isUp && !devicePresent()) {
            log.warn("Serial device {} disappeared; closing inverter slave", port);
            closeQuietly();
            alerts.raise("INVERTER_RTU_DOWN", "USB/RS485 adapter missing: " + port, AlertService.Severity.ERROR);
            return;
        }
        if (isUp) return;

        try {
            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            ModbusSlaveSet newSlave = new ModbusFactory().createRtuSlave(wrapper);
            BasicProcessImage newImage = new BasicProcessImage(slaveId);

            // Initialize BOTH spaces up to WRITE_REG_COUNT
            for (int i = 0; i < WRITE_REG_COUNT; i++) {
                newImage.setInputRegister(i,  (short) 0);  // 04
                newImage.setHoldingRegister(i,(short) 0);  // 03
            }

            newSlave.addProcessImage(newImage);
            newSlave.start();

            synchronized (writeLock) {
                slaveSet     = newSlave;
                processImage = newImage;
                isUp         = true;
            }
            log.info("Inverter-slave opened: port={} baud={} regInit={}", port, baudRate, WRITE_REG_COUNT);
            alerts.resolve("INVERTER_RTU_DOWN");
        } catch (ModbusInitException e) {
            alerts.raise("INVERTER_RTU_DOWN", "Inverter-slave open failed (ModbusInit): " + e.getMessage(), AlertService.Severity.ERROR);
        } catch (Exception e) {
            alerts.raise("INVERTER_RTU_DOWN", "Inverter-slave open failed (unexpected): " + e.getMessage(), AlertService.Severity.ERROR);
        }
    }

    private void closeQuietly() {
        synchronized (writeLock) {
            try { if (slaveSet != null) slaveSet.stop(); } catch (Exception ignore) {}
            slaveSet = null;
            processImage = null;
            isUp = false;
        }
        log.info("Inverter-slave closed");
    }

    /** Build & publish one frame. */
    private void pushOneCycle() {
        try {
            if (!isUp || processImage == null || !devicePresent()) return;

            SmSnapshot snapshot = smReader.getLatestSnapshotSM();
            double compensateKw = loadOverride.getCurrentDeltaKw();

            // Build the outgoing *Acrel raw* image (same size / addresses).
            short[] out = powerControlService.prepareOutputWords(snapshot, compensateKw);
            setOutputData(out);
            lastBuildMs = System.currentTimeMillis();

            if (log.isDebugEnabled()) {
                log.debug("SM snapshot slice(97..123): {}", Arrays.toString(Arrays.copyOfRange(snapshot.data, 97, 123)));
                log.debug("OUT slice(97..123): {}", Arrays.toString(Arrays.copyOfRange(out, 97, 123)));
            }

            synchronized (writeLock) {
                if (processImage == null) return;
                int n = Math.min(out.length, WRITE_REG_COUNT);
                for (int i = 0; i < n; i++) {
                    processImage.setInputRegister(i,  out[i]); // function 04
                    processImage.setHoldingRegister(i,out[i]); // function 03
                }
                lastWriteMs = System.currentTimeMillis();
            }
        } catch (Exception e) {
            alerts.raise("INVERTER_WRITE_FAIL", "Inverter-slave write failed: " + e.getMessage(), AlertService.Severity.WARN);
            closeQuietly();
        }
    }

    private boolean devicePresent() {
        if (port == null || !port.startsWith("/")) return true; // Windows COMx → not a real path
        try {
            Path real = Path.of(port).toRealPath();
            return Files.isReadable(real);
        } catch (Exception e) {
            return false;
        }
    }
}
