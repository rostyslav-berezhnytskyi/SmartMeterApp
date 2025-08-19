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

@Slf4j
@Service
@Getter
@Setter
public class ModbusReaderService {

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

    private volatile short[] latestRawData = new short[72]; // 36 float значень

    @PostConstruct
    public void startReading() {
        new Thread(() -> {
            try {
                ModbusFactory factory = new ModbusFactory();
                SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
                ModbusMaster master = factory.createRtuMaster(wrapper);
                master.init();

                while (true) {
                    ReadInputRegistersRequest request = new ReadInputRegistersRequest(slaveId, startOffset, numberOfRegisters);
                    ReadInputRegistersResponse response = (ReadInputRegistersResponse) master.send(request);

                    if (!response.isException()) {
                        latestRawData = response.getShortData();
                    }

                    Thread.sleep(pollInterval);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
