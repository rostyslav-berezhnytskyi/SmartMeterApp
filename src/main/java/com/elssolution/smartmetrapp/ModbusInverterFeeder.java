package com.elssolution.smartmetrapp;

import com.serotonin.modbus4j.BasicProcessImage;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@Getter @Setter
public class ModbusInverterFeeder {

    // === Dependencies ===
    private final ScheduledExecutorService scheduler;
    private final ModbusSmReader smReader;            // reads raw data from Smart Meter
    private final LoadOverrideService loadOverride;   // how much extra power (kW) we should compensate
    private final PowerController powerController;    // builds the output Modbus word array for the inverter
    private final AlertService alerts;

    public ModbusInverterFeeder(ScheduledExecutorService scheduler,
                                ModbusSmReader smReader,
                                LoadOverrideService loadOverride,
                                PowerController powerController, AlertService alerts) {
        this.scheduler = scheduler;
        this.smReader = smReader;
        this.loadOverride = loadOverride;
        this.powerController = powerController;
        this.alerts = alerts;
    }

    // === Config ===
    @Value("${serial.output.slaveId}")
    private int slaveId;

    @Value("${serial.output.port}")
    private String port;

    @Value("${serial.output.baudRate}")
    private int baudRate;

    /** How many input registers we initialise/write. Increase if you expose more words. */
    private static final int WRITE_REG_COUNT = 100;

    // === Runtime state (guarded by writeLock where noted) ===
    /** Guards concurrent access between tick() and open/close. */
    private final Object writeLock = new Object();

    /** Current “process image” (the registers the inverter will read). Null until opened. */
    private volatile BasicProcessImage processImage;

    /** The actual Modbus-RTU slave server bound to the serial port. Null until opened. */
    private volatile ModbusSlaveSet slaveSet;

    /** Simple connection flag so the open-loop doesn’t re-open unnecessarily. */
    private volatile boolean isUp = false;

    private short[] outputData;

    private volatile long lastWriteMs = 0L;
    private volatile long lastBuildMs = 0L;

    // === Lifecycle ===
    @PostConstruct
    public void start() {
        // Try to (re)open the serial slave every 5s if it’s down
        scheduler.scheduleWithFixedDelay(this::ensureOpen, 0, 5, TimeUnit.SECONDS);
        // Push data to the inverter once per second
        scheduler.scheduleAtFixedRate(this::pushOneCycle, 1, 1, TimeUnit.SECONDS);
    }

    // === Open/Close ===

    /** Open the Modbus-RTU slave if it's not up yet. Safe to call repeatedly. */
    private void ensureOpen() {
        if (isUp) return;
        try {
            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            ModbusSlaveSet newSlave = new ModbusFactory().createRtuSlave(wrapper);
            BasicProcessImage newImage = new BasicProcessImage(slaveId);

            // Pre-zero a reasonable range of input registers so reader sees clean data
            for (int i = 0; i < WRITE_REG_COUNT; i++) {
                newImage.setInputRegister(i, (short) 0);
            }

            newSlave.addProcessImage(newImage);
            newSlave.start();

            synchronized (writeLock) {
                slaveSet     = newSlave;
                processImage = newImage;
                isUp         = true;
            }
            log.info("Inverter-slave opened: port={} baud={} registersInit={}",
                    port, baudRate, WRITE_REG_COUNT);
            alerts.resolve("INVERTER_RTU_DOWN");
        } catch (ModbusInitException e) {
            alerts.raise("INVERTER_RTU_DOWN", "Inverter-slave open failed (ModbusInit): " + e.getMessage(), AlertService.Severity.ERROR);
        } catch (Exception e) {
            alerts.raise("INVERTER_RTU_DOWN", "Inverter-slave open failed (unexpected): " + e.getMessage(), AlertService.Severity.ERROR);
        }
    }

    /** Close the slave quietly and mark “down”. Used on write errors or USB unplug. */
    private void closeQuietly() {
        synchronized (writeLock) {
            try {
                if (slaveSet != null) slaveSet.stop();
            } catch (Exception ignore) {
                // swallow—best effort
            }
            slaveSet = null;
            processImage = null;
            isUp = false;
        }
        log.info("Inverter-slave closed");
        lastWriteMs = System.currentTimeMillis();
    }

    // === Main output loop ===

    /**
     * One I/O cycle:
     *  - get the latest Smart Meter snapshot (immutable)
     *  - get the current grid-import compensation (kW) from Solis
     *  - ask PowerController to prepare the outgoing Modbus words
     *  - write them into the processImage (what the inverter reads)
     */
    private void pushOneCycle() { // work once every 10 sec
        try {
            if (!isUp || processImage == null) return;

            SmSnapshot snapshot = smReader.getLatestSnapshotSM();    // raw data from SM + timestamp of read
            double compensateKw = loadOverride.getCurrentDeltaKw();  // already smoothed/deadbanded data + grid power from SolisAPI

            short[] outputData = powerController.prepareOutputWords(snapshot, compensateKw);
            setOutputData(outputData);

            lastBuildMs = System.currentTimeMillis();
            if (log.isDebugEnabled()) {
                log.debug("SM snapshot data: {}", Arrays.toString(snapshot.data));
                log.debug("Grid import to compensate (kW): {}", compensateKw);
                log.debug("Output data to inverter: {}", Arrays.toString(outputData));
            }

            synchronized (writeLock) {
                if (processImage == null) return; // might have been closed while we built the frame
                int n = Math.min(outputData.length, WRITE_REG_COUNT);
                for (int i = 0; i < n; i++) {
                    processImage.setInputRegister(i, outputData[i]);
                }
                lastWriteMs = System.currentTimeMillis();
            }
        } catch (Exception e) {
            alerts.raise("INVERTER_WRITE_FAIL", "Inverter-slave write failed: " + e.getMessage(), AlertService.Severity.WARN);
            // Typical causes: serial cable unplugged, device reset. We’ll re-open on the next ensureOpen().
            closeQuietly();
        }
    }
}

