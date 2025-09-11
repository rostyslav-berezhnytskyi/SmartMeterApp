package com.elssolution.smartmetrapp.integration.modbus;

import com.elssolution.smartmetrapp.alerts.AlertService;
import com.elssolution.smartmetrapp.alerts.ModbusCrashedEvent;
import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
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

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads the *real* smart meter via Modbus RTU.
 *
 * Modes:
 *  - "acrel": read Holding 97..122 (V/I/Hz) and 356..363 (P1..P3, Ptot),
 *             and publish a RAW Acrel image (short[] index == register address).
 *  - "eastron": legacy path (Input registers, contiguous), published as-is.
 */
@Slf4j
@Service
@Getter @Setter
public class ModbusSmReader {

    // -------- Config (from application.yml) --------
    @Value("${smartmetr.kind:acrel}")    private String meterKind; // 'acrel' or 'eastron'

    @Value("${serial.input.port}")       private String port;
    @Value("${serial.input.baudRate}")   private int baudRate;
    @Value("${serial.input.slaveId}")    private int slaveId;

    // Eastron-only (contiguous Input regs)
    @Value("${serial.input.startOffset:0}")     private int startOffset;
    @Value("${serial.input.numberOfRegisters:72}") private int numberOfRegisters;

    // Poll timing / resilience
    @Value("${serial.input.pollInterval:1000}") private int pollInterval;
    @Value("${serial.input.initialOpenDelayMs:2000}") private int initialOpenDelayMs;
    @Value("${serial.input.reopenBackoffMs:2000}")    private int reopenBackoffMs;
    @Value("${serial.input.warmupMs:2000}")           private int warmupMs;
    @Value("${serial.input.timeoutsBeforeReopen:3}")  private int timeoutsBeforeReopen;

    // Scaling (used only for decoding in StatusService; pass-through is raw anyway)
    @Value("${smartmetr.scale.pt:1.0}") private double acrelPt;
    @Value("${smartmetr.scale.ct:1.0}") private double acrelCt;

    // We allocate a raw image big enough to place Acrel addresses directly
    private static final int RAW_ACREL_IMAGE_LEN = 400; // > 363 safe headroom

    // last-good snapshot
    private volatile SmSnapshot latestSnapshotSM = new SmSnapshot(new short[RAW_ACREL_IMAGE_LEN], 0L);

    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> loopHandle;

    private final AlertService alerts;

    // persistent Modbus master
    private final Object masterLock = new Object();
    private volatile ModbusMaster master;
    private volatile boolean stopping = false;
    private volatile long lastOpenAt = 0L;
    private volatile int consecutiveTimeouts = 0;

    public ModbusSmReader(ScheduledExecutorService scheduler, AlertService alerts) {
        this.scheduler = scheduler;
        this.alerts = alerts;
    }

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

    private void readOnceSafe() {
        try {
            ensureOpen();

            if ("acrel".equalsIgnoreCase(meterKind)) {
                // --- Acrel: read two holding blocks and place them at their actual addresses ---
                ReadHoldingRegistersResponse rVI =
                        (ReadHoldingRegistersResponse) master.send(new ReadHoldingRegistersRequest(slaveId, 97, 26));  // 97..122
                if (rVI.isException()) throw new RuntimeException("Acrel blk1 ex: " + rVI.getExceptionMessage());

                ReadHoldingRegistersResponse rP  =
                        (ReadHoldingRegistersResponse) master.send(new ReadHoldingRegistersRequest(slaveId, 356, 8));  // 356..363
                if (rP.isException()) throw new RuntimeException("Acrel blk2 ex: " + rP.getExceptionMessage());

                short[] out = new short[RAW_ACREL_IMAGE_LEN];
                // Put 97..122 at indices 97..122
                short[] b1 = rVI.getShortData();
                for (int i = 0; i < b1.length; i++) out[97 + i] = b1[i];
                // Put 356..363 at indices 356..363
                short[] b2 = rP.getShortData();
                for (int i = 0; i < b2.length; i++) out[356 + i] = b2[i];

                latestSnapshotSM = new SmSnapshot(out, System.currentTimeMillis());
                consecutiveTimeouts = 0;
                alerts.resolve("METER_DISCONNECTED");
                alerts.resolve("MODBUS_UNCAUGHT");
                if (log.isDebugEnabled()) {
                    log.debug("acrel_read_ok 97..122={}, 356..363={}",
                            Arrays.toString(Arrays.copyOfRange(out, 97, 123)),
                            Arrays.toString(Arrays.copyOfRange(out, 356, 364)));
                }
                return;
            }

            // --- Eastron (legacy contiguous input registers) ---
            ReadInputRegistersResponse resp = (ReadInputRegistersResponse)
                    master.send(new ReadInputRegistersRequest(slaveId, startOffset, numberOfRegisters));

            if (!resp.isException()) {
                consecutiveTimeouts = 0;
                latestSnapshotSM = new SmSnapshot(resp.getShortData(), System.currentTimeMillis());
                alerts.resolve("METER_DISCONNECTED");
                alerts.resolve("MODBUS_UNCAUGHT");
            } else {
                throw new RuntimeException("modbus_exception code=" + resp.getExceptionCode()
                        + " msg=" + resp.getExceptionMessage());
            }

        } catch (ModbusTransportException e) {
            if (isTimeout(e)) {
                consecutiveTimeouts++;
                long sinceOpen = System.currentTimeMillis() - lastOpenAt;
                boolean inWarmup = (sinceOpen >= 0 && sinceOpen < Math.max(0, warmupMs));
                if (inWarmup) {
                    log.warn("modbus_timeout during warmup ({} ms since open, #{}) — keeping port open", sinceOpen, consecutiveTimeouts);
                    return;
                }
                if (consecutiveTimeouts < Math.max(1, timeoutsBeforeReopen)) {
                    log.warn("modbus_timeout (streak #{}) — retrying without reopen", consecutiveTimeouts);
                    return;
                }
                log.warn("modbus_timeout (streak #{}) — will close & reopen", consecutiveTimeouts);
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

    private boolean isTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) return true;
            if (c instanceof SerialPortTimeoutException) return true;
        }
        return false;
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(Math.min(5000, Math.max(200, ms))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
        log.warn("modbus_crash_event → forcing reopen (cause: {})", evt.cause());
        closeQuietly();
        try { Thread.sleep(Math.min(3000, Math.max(500, pollInterval))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
