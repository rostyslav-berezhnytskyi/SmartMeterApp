package com.elssolution.smartmetrapp;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    @Value("${serial.input.port}")
    private String port;

    @Value("${serial.input.baudRate}")
    private int baudRate;

    @Value("${serial.input.slaveId}")
    private int slaveId;

    @Value("${serial.input.startOffset}")
    private int startOffset;

    /** Poll period in milliseconds. */
    @Value("${serial.input.pollInterval}")
    private int pollInterval;

    @Value("${serial.input.numberOfRegisters}")
    private int numberOfRegisters;

    // ==== Last-good snapshot (visible to other threads) ====
    private volatile SmSnapshot latestSnapshotSM = new SmSnapshot(new short[72], 0L);

    // ==== Scheduler injected by your RuntimeConfig ====
    private final ScheduledExecutorService scheduler;

    private final AlertService alerts;

    // ==== Persistent Modbus master (the open serial/RTU connection) ====
    private final Object masterLock = new Object();
    private volatile ModbusMaster master; // created once; reopened on failure

    public ModbusSmReader(ScheduledExecutorService scheduler, AlertService alerts) {
        this.scheduler = scheduler;
        this.alerts = alerts;
    }

    @PostConstruct
    public void startReading() {
        // Fixed delay: after each read completes, wait pollInterval before next read
        scheduler.scheduleWithFixedDelay(this::readOnceSafe, 0, pollInterval, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
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
                latestSnapshotSM = new SmSnapshot(resp.getShortData(), System.currentTimeMillis());
                if (log.isDebugEnabled()) {
                    log.debug("modbus_read_ok words={} start={} slave={}",
                            resp.getShortData().length, startOffset, slaveId);
                }
            } else {
                log.warn("modbus_exception code={} msg={}",
                        resp.getExceptionCode(), resp.getExceptionMessage());
            }

        } catch (Exception e) {
            alerts.raise("METER_DISCONNECTED", "SM read/open failed: " + e.getMessage(), AlertService.Severity.ERROR);
            // Close so the next tick will reopen a fresh port
            closeQuietly();

            // Small backoff to avoid hot loop if the device is unplugged
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(5000, Math.max(500, pollInterval)));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
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
            m.setTimeout(700); // per-request timeout (ms)
            m.setRetries(1);   // minimal library retries (scheduler handles overall retry)
            m.init();          // actually opens the serial port

            master = m;
            log.info("meter_port_opened port={} baud={}", port, baudRate);
            alerts.resolve("METER_DISCONNECTED");
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
}
