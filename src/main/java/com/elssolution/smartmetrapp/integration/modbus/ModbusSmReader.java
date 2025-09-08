package com.elssolution.smartmetrapp.integration.modbus;

import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.elssolution.smartmetrapp.alerts.AlertService;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.springframework.context.event.EventListener;
import com.elssolution.smartmetrapp.alerts.ModbusCrashedEvent;

import com.serotonin.modbus4j.sero.messaging.TimeoutException;
import com.fazecast.jSerialComm.SerialPortTimeoutException; // jSerialComm timeout

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;


/**
 * Reads input registers from the smart meter via Modbus RTU.
 * Plan B: keep the serial port open across reads; if an I/O error happens,
 * close and reopen on the next tick.
 */
@Slf4j
@Service
@Getter @Setter
public class ModbusSmReader {

    // ==== Configuration (from application.yml / env) ====
    @Value("${serial.input.port}")      private String port;
    @Value("${serial.input.baudRate}")      private int baudRate;
    @Value("${serial.input.slaveId}")        private int slaveId;
    @Value("${serial.input.startOffset}")       private int startOffset;
    /** Poll period in milliseconds. */
    @Value("${serial.input.pollInterval}")      private int pollInterval;
    @Value("${serial.input.numberOfRegisters}")        private int numberOfRegisters;

    @Value("${serial.input.initialOpenDelayMs:2000}")   private int initialOpenDelayMs; // delay before first open
    @Value("${serial.input.reopenBackoffMs:2000}")      private int reopenBackoffMs;    // extra backoff on failure

    @Value("${serial.input.warmupMs:2000}")
    private int warmupMs;  // after an open, tolerate timeouts for this long
    @Value("${serial.input.timeoutsBeforeReopen:3}")
    private int timeoutsBeforeReopen; // only after this many consecutive timeouts do we reopen

    // ==== Last-good snapshot (visible to other threads) ====
    private volatile SmSnapshot latestSnapshotSM = new SmSnapshot(new short[72], 0L);

    // ==== Scheduler injected by your RuntimeConfig ====
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> loopHandle;

    private final AlertService alerts;

    // ==== Persistent Modbus master (the open serial/RTU connection) ====
    private final Object masterLock = new Object();
    private volatile ModbusMaster master; // created once; reopened on failure

    private volatile boolean stopping = false; // won`t spam alerts while stopping

    // throttle repeated “open failed” logs/alerts on startup
    private volatile long lastOpenFailLog = 0L;

    // --- internal state for the policy ---
    private volatile long lastOpenAt = 0L;     // when we last opened the port successfully
    private volatile int consecutiveTimeouts = 0;

    public ModbusSmReader(ScheduledExecutorService scheduler, AlertService alerts) {
        this.scheduler = scheduler;
        this.alerts = alerts;
    }

    @PostConstruct
    public void startReading() {
        // Fixed delay: after each read completes, wait pollInterval before next read
        long delay = Math.max(0, initialOpenDelayMs);
        loopHandle = scheduler.scheduleWithFixedDelay(this::readOnceSafe, delay, pollInterval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        ScheduledFuture<?> h = loopHandle;
        if (h != null) h.cancel(false);  // do not interrupt an in-flight read
        closeQuietly(); // close port cleanly on app shutdown
    }

    /**
     * One read cycle (bounded).
     *  - Ensure the port is open (open once, reuse).
     *  - Send ReadInputRegisters.
     *  - Publish snapshot on success.
     *  - On any error: log, close port, and let next tick reopen it.
     */
    private void readOnceSafe() {
        try {
            ensureOpen();

            ReadInputRegistersRequest req =
                    new ReadInputRegistersRequest(slaveId, startOffset, numberOfRegisters);

            ReadInputRegistersResponse resp = (ReadInputRegistersResponse) master.send(req);

            if (!resp.isException()) {
                // SUCCESS → reset timeout streak
                consecutiveTimeouts = 0;
                latestSnapshotSM = new SmSnapshot(resp.getShortData(), System.currentTimeMillis());
                // mark recovered on any good frame
                if (!stopping) {
                    alerts.resolve("METER_DISCONNECTED");
                    alerts.resolve("MODBUS_UNCAUGHT");
                }
                if (log.isDebugEnabled()) {
                    log.debug("modbus_read_ok words={} start={} slave={}",
                            resp.getShortData().length, startOffset, slaveId);
                }
            } else {
                // treat Modbus exception as an I/O failure so we re-open
                throw new RuntimeException("modbus_exception code=" + resp.getExceptionCode()
                        + " msg=" + resp.getExceptionMessage());
            }

        } catch (ModbusTransportException e) {
            // Common path when the device is waking up: read/write timeouts
            if (isTimeout(e)) {
                consecutiveTimeouts++;

                long sinceOpen = System.currentTimeMillis() - lastOpenAt;
                boolean inWarmup = sinceOpen >= 0 && sinceOpen < Math.max(0, warmupMs);

                if (inWarmup) {
                    // Ignore timeouts during warm-up; keep port open and just try again next tick
                    log.warn("modbus_timeout during warmup ({} ms since open, #{}) — keeping port open",
                            sinceOpen, consecutiveTimeouts);
                    return;
                }

                if (consecutiveTimeouts < Math.max(1, timeoutsBeforeReopen)) {
                    // Not enough consecutive timeouts yet — keep port open
                    log.warn("modbus_timeout (streak #{}) — retrying without reopen", consecutiveTimeouts);
                    return;
                }

                // Too many in a row → fall through to reopen below
                log.warn("modbus_timeout (streak #{}) — will close & reopen", consecutiveTimeouts);
            } else {
                // not a timeout — log what it is
                log.warn("modbus_transport_err: {}", e.toString());
            }

            // Reopen path (timeouts exceeded OR other transport error)
            if (!stopping) {
                alerts.raise("METER_DISCONNECTED", "SM read/open failed: " + e, AlertService.Severity.ERROR);
            }
            closeQuietly();
            sleepQuiet(reopenBackoffMs);

        } catch (Throwable e) {
            // non-transport errors
            if (!stopping) {
                alerts.raise("METER_DISCONNECTED", "SM read/open failed: " + e, AlertService.Severity.ERROR);
            }
            closeQuietly();
            sleepQuiet(reopenBackoffMs);
        }
    }

    private boolean isTimeout(Throwable e) {
        // unwrap causes to see if it is a known timeout type
        Throwable c = e;
        while (c != null) {
            if (c instanceof TimeoutException) return true;                // modbus4j timeout
            if (c instanceof SerialPortTimeoutException) return true;      // jSerialComm timeout
            c = c.getCause();
        }
        return false;
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(Math.min(5000, Math.max(200, ms))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * Open the Modbus RTU master once. If already open, do nothing.
     * Thread-safe with a simple lock; 'master' stays volatile for visibility.
     */
    private void ensureOpen() throws Exception {
        if (master != null) return; // fast path

        synchronized (masterLock) {
            if (master != null) return; // double-check

            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            ModbusMaster m = new ModbusFactory().createRtuMaster(wrapper);
            m.setTimeout(1200); // per-request timeout (ms)
            m.setRetries(0);   // minimal library retries (scheduler handles overall retry)
            m.init();          // actually opens the serial port

            master = m;
            consecutiveTimeouts = 0;                 // reset the streak
            lastOpenAt = System.currentTimeMillis(); // start warm-up window
            log.info("meter_port_opened port={} baud={}", port, baudRate);

            // tiny settle delay right after open (helps some USB adapters)
            sleepQuiet(200);
        }
    }

    /**
     * Close the Modbus master if open. Safe to call multiple times.
     * After this, next read will reopen the port.
     */
    private void closeQuietly() {
        synchronized (masterLock) {
            if (master != null) {
                try {
                    master.destroy(); // closes SerialPortWrapperImpl under the hood
                } catch (Exception ignore) { }
                finally {
                    master = null;
                    log.info("meter_port_closed port={}", port);
                }
            }
        }
    }

    @EventListener
    public void onModbusCrash(ModbusCrashedEvent evt) {
        if (stopping) return;
        log.warn("modbus_crash_event → forcing reopen (cause: {})", evt.cause().toString());
        closeQuietly();
        // Optional: a short backoff to let the OS/driver settle
        try {
            Thread.sleep(Math.min(3000, Math.max(500, pollInterval)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
