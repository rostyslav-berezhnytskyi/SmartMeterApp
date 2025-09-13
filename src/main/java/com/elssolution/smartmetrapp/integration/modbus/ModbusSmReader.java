package com.elssolution.smartmetrapp.integration.modbus;

import com.elssolution.smartmetrapp.alerts.AlertService;
import com.elssolution.smartmetrapp.alerts.ModbusCrashedEvent;
import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import com.serotonin.modbus4j.sero.messaging.TimeoutException;
import com.fazecast.jSerialComm.SerialPortTimeoutException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Acrel-only Modbus RTU reader.
 *
 * We poll two register blocks:
 *   - 97..122   (26 regs) : per-phase U/I + misc (raw Acrel units)
 *   - 356..363  (8 regs)  : P L1/L2/L3/Total (i32 be), raw=W/(PT*CT)
 *
 * We publish a single short[] "image" whose indices match the Acrel addresses.
 * All other words are left as 0.
 */
@Slf4j
@Service
@Getter @Setter
public class ModbusSmReader {
    // ==== Config ====
    @Value("${serial.input.port}")        private String port;
    @Value("${serial.input.baudRate}")    private int baudRate;
    @Value("${serial.input.slaveId}")     private int slaveId;
    @Value("${serial.input.pollInterval}")private int pollInterval;

    @Value("${serial.input.initialOpenDelayMs:2000}") private int initialOpenDelayMs;
    @Value("${serial.input.reopenBackoffMs:2000}")    private int reopenBackoffMs;
    @Value("${serial.input.warmupMs:2000}")           private int warmupMs;
    @Value("${serial.input.timeoutsBeforeReopen:3}")  private int timeoutsBeforeReopen;

    // stale-frame watchdog tuning
    @Value("${serial.input.meterStaleMs:30000}")      private long meterStaleMs;
    @Value("${serial.input.staleAlertMinPeriodMs:10000}") private long staleAlertMinPeriodMs; // throttle raises

    // reopen if too many windows fail in one pass
    @Value("${serial.input.maxWindowErrorsBeforeReopen:3}") private int maxWindowErrorsBeforeReopen;

    // Constant
    private static final int RAW_LEN = 400;
    private static final int IMAGE_MAX = 399; // mirror 0..399
    private static final int BLOCK_LEN = 60;  // safe per-request chunk (<=125)

    // ==== State exposed to others ====
    private volatile SmSnapshot latestSnapshotSM = new SmSnapshot(new short[RAW_LEN], 0L); // ≥363

    // ==== Infra ====
    private final ScheduledExecutorService scheduler;
    private final AlertService alerts;
    private volatile ScheduledFuture<?> loopHandle;

    // ==== Modbus master lifecycle ====
    private final Object masterLock = new Object();
    private volatile ModbusMaster master;
    private volatile boolean stopping = false;
    private volatile long lastOpenAt = 0L;
    private volatile int consecutiveTimeouts = 0;

    // local throttle for METER_STALE raises
    private volatile long lastStaleAlertAt = 0L;

    public ModbusSmReader(ScheduledExecutorService scheduler, AlertService alerts) {
        this.scheduler = scheduler;
        this.alerts = alerts;
    }

    // ---- Lifecycle ----
    @PostConstruct
    public void startReading() {
        long delay = Math.max(0, initialOpenDelayMs);
        loopHandle = scheduler.scheduleWithFixedDelay(this::readOnceSafe, delay, pollInterval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        ScheduledFuture<?> h = loopHandle;
        if (h != null) h.cancel(false);
        closeQuietly();
    }

    // ---- Read loop ----
    private void readOnceSafe() {
        if (!devicePresent()) {
            alerts.raise("METER_DISCONNECTED", "Serial device missing: " + port, AlertService.Severity.ERROR);
            closeQuietly();
            sleepQuiet(reopenBackoffMs);
            return;
        }

        try {
            // ---- stale-frame watchdog (no extra scheduler needed) ----
            long ts = latestSnapshotSM.updatedAtMs;
            if (ts != 0) {
                long now = System.currentTimeMillis();
                long age = now - ts;
                boolean pastWarmup = (now - lastOpenAt) > Math.max(0, warmupMs);
                if (pastWarmup && age > meterStaleMs) {
                    if ((now - lastStaleAlertAt) > Math.max(1000L, staleAlertMinPeriodMs)) { // throttle
                        alerts.raise("METER_STALE",
                                "Last good meter frame " + age + " ms ago",
                                AlertService.Severity.ERROR);
                        lastStaleAlertAt = now;
                    }
                } else if (age <= meterStaleMs) {
                    alerts.resolve("METER_STALE");
                }
            }

            ensureOpen();
            short[] img = readAcrelFrame(); // may throw RuntimeException if too many window errors

            // SUCCESS
            latestSnapshotSM = new SmSnapshot(img, System.currentTimeMillis());
            consecutiveTimeouts = 0;
            if (!stopping) {
                alerts.resolve("METER_DISCONNECTED");
                alerts.resolve("MODBUS_UNCAUGHT");
                alerts.resolve("METER_STALE");
            }
            // reset local stale throttle on success
            lastStaleAlertAt = 0L;

        } catch (ModbusTransportException e) {
            // Common path when the device is waking up: read/write timeouts
            if (isTimeout(e)) {
                consecutiveTimeouts++;
                long sinceOpen = System.currentTimeMillis() - lastOpenAt;
                boolean inWarmup = sinceOpen >= 0 && sinceOpen < Math.max(0, warmupMs);

                if (inWarmup) {
                    log.warn("modbus_timeout during warmup ({} ms since open, #{}) — keep port", sinceOpen, consecutiveTimeouts);
                    return;
                }
                if (consecutiveTimeouts < Math.max(1, timeoutsBeforeReopen)) {
                    log.warn("modbus_timeout (streak #{}) — retry without reopen", consecutiveTimeouts);
                    return;
                }
                log.warn("modbus_timeout (streak #{}) — closing & reopening", consecutiveTimeouts);
            } else {
                log.warn("modbus_transport_err: {}", e.toString());
            }
            if (!stopping) alerts.raise("METER_DISCONNECTED", "SM read/open failed: " + e, AlertService.Severity.ERROR);
            closeQuietly();
            sleepQuiet(reopenBackoffMs);

        } catch (Throwable e) {
            // non-transport errors OR “too many window errors” from readAcrelFrame
            if (!stopping) alerts.raise("METER_DISCONNECTED", "SM read/open failed: " + e, AlertService.Severity.ERROR);
            closeQuietly();
            sleepQuiet(reopenBackoffMs);
        }
    }

    /**
     * Mirror 0..399 in 60-reg windows. Skip isolated bad windows,
     * but if too many windows fail in one pass → force reopen.
     */
    private short[] readAcrelFrame() {
        short[] out = new short[RAW_LEN]; // RAW_LEN is 400

        int failed = 0;
        for (int start = 0; start <= IMAGE_MAX; start += BLOCK_LEN) {
            int len = Math.min(BLOCK_LEN, IMAGE_MAX - start + 1);
            try {
                ReadHoldingRegistersResponse r = (ReadHoldingRegistersResponse)
                        master.send(new ReadHoldingRegistersRequest(slaveId, start, len));
                if (!r.isException()) {
                    short[] data = r.getShortData();
                    int copy = Math.min(len, data.length);
                    System.arraycopy(data, 0, out, start, copy);
                } // if exception: meter might not implement this window → just skip
                else {
                    failed++;
                    if (log.isDebugEnabled()) {
                        log.debug("modbus_exception window {}..{} code={} msg={}",
                                start, start + len - 1, r.getExceptionCode(), r.getExceptionMessage());
                    }
                }
            } catch (ModbusTransportException ex) {
                // Skip this window and continue with the next; don't break the whole image
                failed++;
                if (log.isTraceEnabled()) log.trace("skip window {}..{}: {}", start, start + len - 1, ex.toString());
            } catch (Exception ex) {
                failed++;
                if (log.isDebugEnabled()) log.debug("unexpected window error @{}: {}", start, ex.toString());
            }
        }

        if (failed > 0) {
            log.warn("acrel_read_partial: {} windows failed in this pass", failed);
        }
        if (failed >= Math.max(1, maxWindowErrorsBeforeReopen)) {
            // force outer catch → reopen; don’t publish a “holey” image
            throw new RuntimeException("too many window errors: " + failed);
        }

        if (log.isDebugEnabled()) {
            log.debug("acrel_full_read_ok: mirrored holding 0..{}", IMAGE_MAX);
        }
        return out;
    }

    // ---- Helpers ----
    private boolean isTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) return true;
            if (c instanceof SerialPortTimeoutException) return true;
        }
        return false;
    }

    private void ensureOpen() throws Exception {
        if (master != null) return;
        synchronized (masterLock) {
            if (master != null) return;
            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            ModbusMaster m = new ModbusFactory().createRtuMaster(wrapper);
            m.setTimeout(1200);
            m.setRetries(0);
            m.init();
            master = m;
            consecutiveTimeouts = 0;
            lastOpenAt = System.currentTimeMillis();
            log.info("meter_port_opened port={} baud={}", port, baudRate);
            sleepQuiet(200);
        }
    }

    private void closeQuietly() {
        synchronized (masterLock) {
            if (master != null) {
                try {
                    master.destroy();
                } catch (Exception ignore) {
                    // best-effort
                } finally {
                    master = null;
                    log.info("meter_port_closed port={}", port);
                }
            }
        }
    }

    private boolean devicePresent() {
        if (port == null || !port.startsWith("/")) return true; // Windows COMx
        try {
            java.nio.file.Path p = java.nio.file.Path.of(port).toRealPath();
            return java.nio.file.Files.isReadable(p);
        } catch (Exception e) {
            return false;
        }
    }

    @EventListener
    public void onModbusCrash(ModbusCrashedEvent evt) {
        if (stopping) return;
        log.warn("modbus_crash_event → forcing reopen (cause: {})", evt.cause().toString());
        closeQuietly();
        try { Thread.sleep(Math.min(3000, Math.max(500, pollInterval))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(Math.min(5000, Math.max(200, ms))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
