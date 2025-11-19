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
    @Value("${serial.output.initRegisters:0}")
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

    /** How long after first GOOD meter frame we stay in pure pass-through mode (ms). */
    @Value("${serial.output.warmupPassThroughMs:10000}")
    private long warmupPassThroughMs;                    // NEW

    // ===== Runtime state =====
    private final Object lock = new Object();
    private volatile AtomicSnapshotImage image;    // current process image
    private volatile ModbusSlaveSet slave;        // serial Modbus slave
    private volatile boolean up = false;

    private volatile short[] outputData;          // last frame we published (for UI)
    private volatile long lastWriteMs = 0L;       // last successful publish time

    /** Time of first good (fresh) SM frame after start/restart. */
    private volatile long firstGoodFrameAt = 0L;         // NEW

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
            // IMPORTANT: slave must be 0/0 timeouts
            SerialPortWrapper wrapper =
                    new SerialPortWrapperImpl(port, baudRate, /*read*/0, /*write*/0, /*forSlave*/ true);
            ModbusSlaveSet newSlave = new ModbusFactory().createRtuSlave(wrapper);

            // Honor initRegisters, but never block on a big prefill—start at 0 length.
            int initialLen = Math.max(0, initRegisters);
            AtomicSnapshotImage newImage = new AtomicSnapshotImage(slaveId, initialLen);

            // --------------------------------------------------------------------------
            // !!! CRITICAL MODBUS STARTUP FIX !!!
            // Pre-load the image with the last known SM snapshot, ensuring the
            // inverter never reads an all-zero image on the first poll after restart.
            // --------------------------------------------------------------------------
            SmSnapshot staleSnap = smReader.getLatestSnapshotSM(); // Get current volatile state
            if (staleSnap != null && staleSnap.updatedAtMs != 0L) {
                // Prepare a non-compensated (0.0 kW) frame using the stale data.
                // This is safe because it only uses the raw meter data to build the frame structure.
                short[] initialFrame = powerControl.prepareOutputWords(staleSnap, 0.0);
                newImage.publish(initialFrame);
                log.info("Inverter-slave image pre-loaded with {} registers of LATEST KNOWN METER data.",
                        initialFrame.length);
            } else {
                log.warn("Inverter-slave image started with zero data. Waiting for first SM frame.");
            }
            // --------------------------------------------------------------------------


            newSlave.addProcessImage(newImage);
            newSlave.start();

            synchronized (lock) {
                slave = newSlave;
                image = newImage;
                up = true;
            }
            log.info("Inverter-slave opened: port={} baud={} initRegisters={}", port, baudRate, initRegisters);
            alerts.resolve("INVERTER_RTU_DOWN");

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
        firstGoodFrameAt = 0L;  // reset warmup marker on close
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

            if (snap == null || snap.updatedAtMs == 0L) {
                // No first frame yet → keep last frame alive if any
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

            // 2) stale SM input → keep last good frame (fail-safe)
            if (smAge > Math.max(0L, maxSmAgeForWriteMs)) {
                alerts.raise("INVERTER_FEEDER_STALE_INPUT",
                        "Meter input stale: " + smAge + " ms (>" + maxSmAgeForWriteMs + " ms)",
                        AlertService.Severity.ERROR);
                if (republishOnStale && outputData != null) {
                    // keep feeding last known good data
                    publishFullFrame(outputData);
                }
                return;
            } else {
                alerts.resolve("INVERTER_FEEDER_STALE_INPUT");
            }

            // 3) mark first good frame time
            if (firstGoodFrameAt == 0L) {
                firstGoodFrameAt = now;
            }

            // 4) Decide: pass-through vs compensated
            boolean inWarmup = (now - firstGoodFrameAt) < Math.max(0L, warmupPassThroughMs);

            final double deltaKw;
            if (inWarmup) {
                // First N ms: behave like a transparent SM → no override
                deltaKw = 0.0;
            } else {
                // Normal mode: use override (this already returns 0 if stale/disabled)
                deltaKw = loadOverride.getCurrentDeltaKw();
            }

            // 5) Build outgoing image
            short[] frame = powerControl.prepareOutputWords(snap, deltaKw);

            // 6) Publish WHOLE FRAME
            publishFullFrame(frame);

            alerts.resolve("INVERTER_WRITE_FAIL");
            alerts.resolve("INVERTER_OUTPUT_STALE");

            if (log.isDebugEnabled()) {
                log.debug("Warmup={} deltaKw={} kW; wrote {} regs (min..max=0..{})",
                        inWarmup, deltaKw,
                        Math.max(initRegisters, frame.length),
                        Math.max(initRegisters, frame.length) - 1);
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
        long age = System.currentTimeMillis() - s.updatedAtMs;
        return age <= Math.max(0L, maxAgeMs);
    }

    // single place that writes the WHOLE frame to 04 & 03
    private void publishFullFrame(short[] frame) {

        AtomicSnapshotImage img = image;
        if (img == null) return;
        img.publish(frame);
        lastWriteMs = System.currentTimeMillis();
        outputData  = (frame != null) ? frame.clone() : null; // avoid future accidental mutation
    }



}
