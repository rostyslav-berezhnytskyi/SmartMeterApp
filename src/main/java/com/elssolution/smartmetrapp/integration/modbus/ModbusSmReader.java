package com.elssolution.smartmetrapp.integration.modbus;

import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.elssolution.smartmetrapp.alerts.AlertService;
import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
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

import com.elssolution.smartmetrapp.domain.MeterDecoder;      // NEW
import com.elssolution.smartmetrapp.domain.MeterRegisterMap; // NEW


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

    //  Acrel
    // ModbusSmReader.java  (fields – add)
    @Value("${smartmetr.kind:eastron}")
    private String meterKind;                  // 'eastron' or 'acrel'

    @Value("${smartmetr.scale.pt:1.0}")
    private double acrelPt;

    @Value("${smartmetr.scale.ct:1.0}")
    private double acrelCt;

    // needed to build an Eastron-like float image from Acrel ints
    private final MeterDecoder codec;          // NEW
    private final MeterRegisterMap map;        // NEW

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

    public ModbusSmReader(MeterDecoder codec, MeterRegisterMap map, ScheduledExecutorService scheduler, AlertService alerts) {
        this.codec = codec;
        this.map = map;
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

            if ("acrel".equalsIgnoreCase(meterKind)) {
                // --- ACREL path: read holding registers (03) in two small blocks ---
                ReadHoldingRegistersResponse rVI =
                        (ReadHoldingRegistersResponse) master.send(
                                new ReadHoldingRegistersRequest(slaveId, 97, 26));   // 97..122
                if (rVI.isException()) throw new RuntimeException("Acrel block1 ex: " + rVI.getExceptionMessage());

                ReadHoldingRegistersResponse rP =
                        (ReadHoldingRegistersResponse) master.send(
                                new ReadHoldingRegistersRequest(slaveId, 356, 8));   // 356..363 (incl. 362 total P)
                if (rP.isException()) throw new RuntimeException("Acrel block2 ex: " + rP.getExceptionMessage());

                short[] words = buildEastronLikeImageFromAcrel(rVI.getShortData(), rP.getShortData());
                latestSnapshotSM = new SmSnapshot(words, System.currentTimeMillis());
                consecutiveTimeouts = 0;
                alerts.resolve("METER_DISCONNECTED");
                alerts.resolve("MODBUS_UNCAUGHT");
                if (log.isDebugEnabled()) log.debug("acrel_read_ok (97..122,356..362) -> imageLen={}", words.length);
                return;
            }

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

    private short[] buildEastronLikeImageFromAcrel(short[] blk97, short[] blk356) {
        // We'll publish an image compatible with your existing map (floats at offsets)
        short[] out = new short[Math.max(100, map.pTotal() + 2)]; // safe size

        // --- A) Voltages (u16 * 0.1 V) ---
        float v1 = 0.1f * u16(blk97, 0);   // addr 97
        float v2 = 0.1f * u16(blk97, 1);   // 98
        float v3 = 0.1f * u16(blk97, 2);   // 99
        codec.writeFloat(out, map.vL1(), v1);
        codec.writeFloat(out, map.vL2(), v2);
        codec.writeFloat(out, map.vL3(), v3);

        // --- B) Currents (u16 * 0.01 A * CT) ---
        float i1 = (float)(0.01 * u16(blk97, 3) * acrelCt);  // 100
        float i2 = (float)(0.01 * u16(blk97, 4) * acrelCt);  // 101
        float i3 = (float)(0.01 * u16(blk97, 5) * acrelCt);  // 102
        codec.writeFloat(out, map.iL1(), i1);
        codec.writeFloat(out, map.iL2(), i2);
        codec.writeFloat(out, map.iL3(), i3);

        // --- C) Total Active Power (i32 * 0.001 kW * PT * CT) -> publish as Watts float ---
        // blk356 is 356.., so index of 362 is (362-356)=6 (MSW,LSW order)
        int pTotRaw = i32be(blk356, 6); // signed 32-bit
        double pTotKw = pTotRaw * 0.001 * acrelPt * acrelCt;
        float pTotW  = (float)(pTotKw * 1000.0);
        codec.writeFloat(out, map.pTotal(), pTotW);

        // (Optional) per-phase P at 356,358,360 if you decide to map pL1..pL3 later:
//         codec.writeFloat(out, map.pL1(), (float)(i32be(blk356, 0)*0.001*acrelPt*acrelCt*1000));
//         codec.writeFloat(out, map.pL2(), (float)(i32be(blk356, 2)*0.001*acrelPt*acrelCt*1000));
//         codec.writeFloat(out, map.pL3(), (float)(i32be(blk356, 4)*0.001*acrelPt*acrelCt*1000));

        float hz = 0.01f * u16(blk97, 22);
        if (map.fHz() >= 0) codec.writeFloat(out, map.fHz(), hz);
        return out;
    }

    // unsigned 16-bit to int
    private static int u16(short[] a, int idx) {
        if (a == null || idx < 0 || idx >= a.length) return 0;
        return a[idx] & 0xFFFF;
    }

    // big-endian 32-bit signed from two 16-bit regs (MSW first)
    private static int i32be(short[] a, int idx) {
        // idx is the index of MSW within the block
        int hi = u16(a, idx);
        int lo = u16(a, idx + 1);
        return (hi << 16) | lo;
    }
}
