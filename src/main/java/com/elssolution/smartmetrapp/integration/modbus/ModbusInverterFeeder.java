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
@Getter
@Setter
public class ModbusInverterFeeder {

    // ===== Dependencies =====
    private final ScheduledExecutorService scheduler;
    private final ModbusSmReader smReader;
    private final LoadOverrideService loadOverride;
    private final PowerControlService powerControl;
    private final AlertService alerts;

    public ModbusInverterFeeder(ScheduledExecutorService scheduler,
                                ModbusSmReader smReader,
                                LoadOverrideService loadOverride,
                                PowerControlService powerControl,
                                AlertService alerts) {
        this.scheduler = scheduler;
        this.smReader = smReader;
        this.loadOverride = loadOverride;
        this.powerControl = powerControl;
        this.alerts = alerts;
    }

    // ===== Config =====
    @Value("${serial.output.slaveId}")  private int    slaveId;
    @Value("${serial.output.port}")     private String port;
    @Value("${serial.output.baudRate}") private int    baudRate;

    /** Pre-zero this many registers on open (04 & 03). Should cover every index the inverter might read. */
    @Value("${serial.output.initRegisters:400}")
    private int initRegisters;

    /** If meter snapshot is older than this, do not overwrite the image (avoid feeding junk). */
    @Value("${serial.output.maxSmAgeForWriteMs:60000}")
    private long maxSmAgeForWriteMs;

    /** Raise INVERTER_OUTPUT_STALE if we haven’t successfully written for this long. */
    @Value("${serial.output.outStaleMs:30000}")
    private long outStaleMs;

    @Value("${serial.output.deferOpenUntilFirstFrame:true}")
    private boolean deferOpenUntilFirstFrame;

    @Value("${serial.output.republishOnStale:true}")
    private boolean republishOnStale;


    // ===== Runtime state =====
    private final Object lock = new Object();
    private volatile BasicProcessImage image;     // current process image
    private volatile ModbusSlaveSet slave;        // serial Modbus slave
    private volatile boolean up = false;

    private volatile short[] outputData;          // last frame we published (for UI)
    private volatile long lastWriteMs = 0L;       // last successful publish time

    // ===== Lifecycle =====
    @PostConstruct
    void start() {
        // Re-open watcher
        scheduler.scheduleWithFixedDelay(this::ensureOpen, 0, 5, TimeUnit.SECONDS);
        // Data push loop
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        // Output staleness watchdog (no annotations)
        scheduler.scheduleWithFixedDelay(this::watchOutputStaleness, 5, 2, TimeUnit.SECONDS);
    }

    // ===== Open/Close =====

    /** Ensure the Modbus slave is up; if device vanishes, close and mark down. */
    // ===== Open/Close =====
    private void ensureOpen() {
        if (up && !devicePresent()) {
            log.warn("Serial device {} disappeared; closing inverter slave", port);
            closeQuietly();
            alerts.raise("INVERTER_RTU_DOWN", "USB/RS485 adapter missing: " + port, AlertService.Severity.ERROR);
            return;
        }
        if (up) return;

        // avoid opening until we have a fresh frame (prevents zero image)
        if (deferOpenUntilFirstFrame && !hasFreshFrame(maxSmAgeForWriteMs)) {
            alerts.raise("INVERTER_FEEDER_WAITING_FOR_METER", "Waiting for first meter frame…",
                    AlertService.Severity.WARN);
            return;
        } else {
            alerts.resolve("INVERTER_FEEDER_WAITING_FOR_METER");
        }

        try {
            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            ModbusSlaveSet newSlave = new ModbusFactory().createRtuSlave(wrapper);
            BasicProcessImage newImage = new BasicProcessImage(slaveId);

            // Keep optional pre-zero, but default is 0 (disabled)
            if (initRegisters > 0) {
                for (int i = 0; i < initRegisters; i++) {
                    newImage.setInputRegister(i, (short) 0);   // 04
                    newImage.setHoldingRegister(i, (short) 0); // 03
                }
            }

            newSlave.addProcessImage(newImage);
            newSlave.start();

            synchronized (lock) {
                slave = newSlave;
                image = newImage;
                up = true;
            }
            log.info("Inverter-slave opened: port={} baud={} initRegisters={}", port, baudRate, initRegisters);
            alerts.resolve("INVERTER_RTU_DOWN");

            // NEW: publish immediately after open (no zero-frame window)
            initialPublishAfterOpen();

        } catch (ModbusInitException e) {
            alerts.raise("INVERTER_RTU_DOWN",
                    "Inverter-slave open failed (ModbusInit): " + e.getMessage(),
                    AlertService.Severity.ERROR);
        } catch (Exception e) {
            alerts.raise("INVERTER_RTU_DOWN",
                    "Inverter-slave open failed (unexpected): " + e.getMessage(),
                    AlertService.Severity.ERROR);
        }
    }


    /** Stop the slave and clear internal state. Safe to call multiple times. */
    private void closeQuietly() {
        synchronized (lock) {
            try { if (slave != null) slave.stop(); }
            catch (Exception ignore) { /* best-effort */ }
            finally {
                slave = null;
                image = null;
                up = false;
            }
        }
        log.info("Inverter-slave closed");
    }

    // ===== Main loop =====

    /** Build one frame and publish it to the Modbus slave. */
    // ===== Main loop =====
    private void tick() {
        try {
            if (!up || image == null || !devicePresent()) return;

            // 1) latest meter snapshot
            SmSnapshot snap = smReader.getLatestSnapshotSM();

            // No first frame yet → keep last frame alive if any
            if (snap == null || snap.updatedAtMs == 0L) {
                alerts.raise("INVERTER_FEEDER_WAITING_FOR_METER", "Waiting for first meter frame…",
                        AlertService.Severity.WARN);
                if (republishOnStale && outputData != null) {
                    publishFullFrame(outputData);
                }
                return;
            } else {
                alerts.resolve("INVERTER_FEEDER_WAITING_FOR_METER");
            }

            long now = System.currentTimeMillis();
            long smAge = now - snap.updatedAtMs;

            // 2) If SM input is stale → re-publish last good frame (do NOT send zeros)
            if (smAge > Math.max(0L, maxSmAgeForWriteMs)) {
                alerts.raise("INVERTER_FEEDER_STALE_INPUT",
                        "Meter input stale: " + smAge + " ms (>" + maxSmAgeForWriteMs + " ms)",
                        AlertService.Severity.ERROR);
                if (republishOnStale && outputData != null) {
                    publishFullFrame(outputData);
                    return;
                } else {
                    return; // image keeps previous contents
                }
            } else {
                alerts.resolve("INVERTER_FEEDER_STALE_INPUT");
            }

            // 3) Build full outgoing image (pass-through when override OFF)
            double deltaKw = loadOverride.getCurrentDeltaKw();
            short[] frame = powerControl.prepareOutputWords(snap, deltaKw);

            // 4) Publish WHOLE FRAME (04 & 03)
            publishFullFrame(frame);

            // success → resolve write alerts
            alerts.resolve("INVERTER_WRITE_FAIL");
            alerts.resolve("INVERTER_OUTPUT_STALE");

            if (log.isDebugEnabled()) {
                log.debug("Compensate={} kW; wrote {} regs (min..max={}..{})",
                        deltaKw, Math.max(initRegisters, frame.length), 0, Math.max(initRegisters, frame.length) - 1);
            }

        } catch (Exception e) {
            alerts.raise("INVERTER_WRITE_FAIL",
                    "Inverter-slave write failed: " + e.getMessage(),
                    AlertService.Severity.WARN);
            closeQuietly();
        }
    }


    // Separate watchdog to avoid false positives on boot (lastWriteMs==0)
    private void watchOutputStaleness() {
        if (!up || image == null) return;
        long now = System.currentTimeMillis();
        if (lastWriteMs == 0L) return;
        long age = now - lastWriteMs;
        if (age > Math.max(0L, outStaleMs)) {
            alerts.raise("INVERTER_OUTPUT_STALE",
                    "No inverter feed update for " + age + " ms",
                    AlertService.Severity.ERROR);
        } else {
            alerts.resolve("INVERTER_OUTPUT_STALE");
        }
    }

    // ===== Helpers =====

    /** On Linux we can check /dev/... existence. On Windows (COMx) just return true. */
    private boolean devicePresent() {
        if (port == null || !port.startsWith("/")) return true;
        try {
            Path real = Path.of(port).toRealPath();  // follow by-id symlink if present
            return Files.isReadable(real);
        } catch (Exception e) {
            return false;
        }
    }

    // do we have a fresh meter frame? ===
    private boolean hasFreshFrame(long maxAgeMs) {
        SmSnapshot s = smReader.getLatestSnapshotSM();
        if (s == null || s.updatedAtMs == 0L) return false;
        return (System.currentTimeMillis() - s.updatedAtMs) <= Math.max(0L, maxSmAgeForWriteMs);
    }

    // publish immediately after open using last output or a fresh build
    private void initialPublishAfterOpen() {
        try {
            short[] frame = outputData; // last good
            if (frame == null) {
                SmSnapshot snap = smReader.getLatestSnapshotSM();
                double deltaKw = loadOverride.getCurrentDeltaKw();
                frame = powerControl.prepareOutputWords(snap, deltaKw);
            }
            if (frame != null) {
                publishFullFrame(frame);
                alerts.resolve("INVERTER_OUTPUT_STALE");
            }
        } catch (Exception e) {
            alerts.raise("INVERTER_WRITE_FAIL",
                    "Inverter-slave initial publish failed: " + e.getMessage(),
                    AlertService.Severity.WARN);
            closeQuietly();
        }
    }

    // single place that writes the WHOLE frame to 04 & 03
    private void publishFullFrame(short[] frame) {
        int writeCount = Math.max(initRegisters, frame.length);
        synchronized (lock) {
            if (image == null) return;
            for (int i = 0; i < writeCount; i++) {
                short v = (i < frame.length) ? frame[i] : 0;
                image.setInputRegister(i, v);   // 04
                image.setHoldingRegister(i, v); // 03
            }
            lastWriteMs = System.currentTimeMillis();
        }
        outputData = frame;
    }


}
