package com.elssolution.smartmetrapp;

import com.serotonin.modbus4j.ModbusFactory;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import com.serotonin.modbus4j.BasicProcessImage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ModbusSlaveSimulator {

    @Autowired
    private ModbusReaderService readerService;

    @Autowired
    private LoadOverrideService loadOverrideService;

    @Value("${serial.output.slaveId}")
    private int slaveId;
    @Value("${serial.output.port}")
    private String port; // Віртуальний порт, на який симулюємо віддачу
    @Value("${serial.output.baudRate}")
    private int baudRate;

    private BasicProcessImage processImage;


    private void startDataFeeder() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            short[] data = readerService.getLatestRawData();
            if (data == null || data.length < 72) return;

            // Зчитати існуючі значення
            float voltageL1 = getFloat(data, 0);
            log.info("voltage from SM - " + voltageL1);
            float currentL1 = getFloat(data, 6);
            log.info("current from SM - " + currentL1);
            float power = getFloat(data, 52);  // сумарна потужність
            log.info("power from SM - " + power);

            float deltaKw = loadOverrideService.getOverrideDeltaKw(); // данні з solis обробленні
            if (deltaKw > 0) {
                float cosPhi = 0.95f;

                // Який струм треба додати (всього по 1 фазі)
                float additionalCurrent = deltaKw * 1000 / (voltageL1 * cosPhi);
                currentL1 += additionalCurrent;

                // І відповідно збільшити і потужність
                power += deltaKw * 1000;

                log.info("Додаємо %.2f A до струму (сумарно %.2f A)%n", additionalCurrent, currentL1);
            }

            // Замінюємо дані
            writeFloatToInput(processImage, 6, currentL1);
            writeFloatToInput(processImage, 52, power);

            // Решта даних — як є
            for (int i = 0; i < data.length; i++) {
                if (i == 6 || i == 7 || i == 52 || i == 53) continue; // Пропускаємо оновлені вручну
                processImage.setInputRegister(i, data[i]);
            }

        }, 0, 5, TimeUnit.SECONDS);
    }

    @PostConstruct
    public void startSlave() {
        try {
            SerialPortWrapper wrapper = new SerialPortWrapperImpl(port, baudRate);
            ModbusSlaveSet slave = new ModbusFactory().createRtuSlave(wrapper);

            processImage = new BasicProcessImage(slaveId);
            registerInitialAddresses();

            slave.addProcessImage(processImage);
            slave.start();

            startDataFeeder();

            log.info("Slave started on " + port);
        } catch (ModbusInitException e) {
            log.error("Error when start slave - " + e);
            e.printStackTrace();
        }
    }

    private void registerInitialAddresses() {
        for (int i = 0; i <= 100; i++) {
            processImage.setInputRegister(i, (short) 0);
        }
    }

    private void writeFloatToInput(BasicProcessImage image, int start, float value) {
        short[] words = floatToRegisters(value);
        image.setInputRegister(start, words[0]);
        image.setInputRegister(start + 1, words[1]);
    }

    private short[] floatToRegisters(float value) {
        int bits = Float.floatToIntBits(value);
        short hi = (short)((bits >> 16) & 0xFFFF);
        short lo = (short)(bits & 0xFFFF);
        return new short[]{hi, lo};
    }

    private float getFloat(short[] data, int pos) {
        int word1 = data[pos] & 0xFFFF;
        int word2 = data[pos + 1] & 0xFFFF;
        int bits = (word1 << 16) | word2;
        return Float.intBitsToFloat(bits);
    }
}

