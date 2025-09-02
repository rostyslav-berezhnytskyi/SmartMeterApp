package com.elssolution.smartmetrapp;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Getter @Setter
public class ModbusReaderService {
    // ==== Configuration (injected from application.yml / env) ====
    @Value("${serial.input.port}")
    private String port;
    @Value("${serial.input.baudRate}")
    private int baudRate;
    @Value("${serial.input.slaveId}")
    private int slaveId;
    @Value("${serial.input.startOffset}")
    private int startOffset;
    @Value("${serial.input.pollInterval}")
    private int pollInterval;
    @Value("${serial.input.numberOfRegisters}")
    private int numberOfRegisters;

    // ==== Last-good data for the feeder to publish (volatile for cross-thread visibility) ====
    private volatile short[] latestRawData = new short[72]; // 36 float значень

    // ==== Shared scheduler (singleton) provided by RuntimeConfig ====
    private final ScheduledExecutorService scheduler;

    public ModbusReaderService(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }


    /**
     * Schedule bounded, periodic reads instead of an ad-hoc infinite loop.
     * Using scheduleWithFixedDelay ensures that if a read takes time, we still wait pollInterval
     * after it completes before the next read (prevents tight loops on slow devices).
     */
    @PostConstruct
    public void startReading() {
        scheduler.scheduleWithFixedDelay(this::readOnceSafe, 0, pollInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * One bounded read cycle:
     *  - Open RTU master
     *  - Set IO timeout and small internal retry
     *  - Read input registers
     *  - Publish into latestRawData (for the Modbus slave writer)
     *  - Always destroy master so file descriptors don’t leak
     *  - On error: small backoff so we don’t hammer a broken line
     */
    private void readOnceSafe() {
        ModbusMaster master = null;
        try {
            ModbusFactory factory = new ModbusFactory();
            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            master = factory.createRtuMaster(wrapper);
            master.setTimeout(700); // ms: per-request timeout → avoids indefinite blocking
            master.setRetries(1);   // minimal retry (library-level); the scheduler will try again next tick
            master.init();      // open port + prepare protocol

            ReadInputRegistersRequest req = new ReadInputRegistersRequest(slaveId, startOffset, numberOfRegisters);
            ReadInputRegistersResponse resp = (ReadInputRegistersResponse) master.send(req);

            if (!resp.isException()) {
                latestRawData = resp.getShortData();
            } else {
                log.warn("modbus_exception code={} message={}", resp.getExceptionCode(), resp.getExceptionMessage());
            }
        } catch (Exception e) {
            log.warn("modbus_read_failed msg={}", e.getMessage());
            // Light backoff to avoid a hot error loop when the meter is unplugged/rebooting.
            try {
                TimeUnit.MILLISECONDS.sleep(5000); //ms Light backoff to avoid hot loop on persistent failure
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); // preserve interrupt signal for a clean shutdown
            }
        } finally {
            if (master != null) {
                try { master.destroy();
                } catch (Exception ignore) {}
            }
        }
    }


//    @PostConstruct
//    public void startReading() {
//        new Thread(() -> {
//            try {
//                ModbusFactory factory = new ModbusFactory();
//                SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
//                ModbusMaster master = factory.createRtuMaster(wrapper);
//                master.init();
//
//                while (true) {
//                    ReadInputRegistersRequest request = new ReadInputRegistersRequest(slaveId, startOffset, numberOfRegisters);
//                    ReadInputRegistersResponse response = (ReadInputRegistersResponse) master.send(request);
//
//                    if (!response.isException()) {
//                        latestRawData = response.getShortData();
//                    }
//
//                    Thread.sleep(pollInterval);
//                }
//            } catch (Exception e) {
//                log.error("Some exception when start reading - " + e);
//                e.printStackTrace();
//            }
//        }).start();
//    }
}
