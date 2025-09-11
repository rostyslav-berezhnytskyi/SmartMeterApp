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

    // ==== State exposed to others ====
    private volatile SmSnapshot latestSnapshotSM = new SmSnapshot(new short[400], 0L); // ≥363

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
        try {
            ensureOpen();
            short[] img = readAcrelFrame();
            latestSnapshotSM = new SmSnapshot(img, System.currentTimeMillis());
            consecutiveTimeouts = 0;
            alerts.resolve("METER_DISCONNECTED");
            alerts.resolve("MODBUS_UNCAUGHT");
        } catch (ModbusTransportException e) {
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
            if (!stopping) alerts.raise("METER_DISCONNECTED", "SM read/open failed: " + e, AlertService.Severity.ERROR);
            closeQuietly();
            sleepQuiet(reopenBackoffMs);
        }
    }

    /** One Acrel read: fetch two ranges and place them into a single image array. */
    private short[] readAcrelFrame() throws ModbusTransportException {
        // 97..122 — U/I/Hz etc
        ReadHoldingRegistersResponse r1 = (ReadHoldingRegistersResponse)
                master.send(new ReadHoldingRegistersRequest(slaveId, 97, 26));
        if (r1.isException()) throw new RuntimeException("Acrel 97..122 ex: " + r1.getExceptionMessage());

        // 356..363 — P L1/L2/L3/Total (i32 be)
        ReadHoldingRegistersResponse r2 = (ReadHoldingRegistersResponse)
                master.send(new ReadHoldingRegistersRequest(slaveId, 356, 8));
        if (r2.isException()) throw new RuntimeException("Acrel 356..363 ex: " + r2.getExceptionMessage());

        // Build image
        short[] out = new short[400]; // roomy; >= 364
        copyBlock(out, 97,  r1.getShortData());
        copyBlock(out, 356, r2.getShortData());
        if (log.isDebugEnabled()) log.debug("acrel_read_ok: filled [97..122] & [356..363]");
        return out;
    }

    // ---- Helpers ----
    private static void copyBlock(short[] dest, int startAddr, short[] data) {
        if (dest == null || data == null) return;
        int max = Math.min(dest.length - startAddr, data.length);
        if (max <= 0) return;
        System.arraycopy(data, 0, dest, startAddr, max);
    }

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
                try { master.destroy(); } catch (Exception ignore) {}
                master = null;
                log.info("meter_port_closed port={}", port);
            }
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
