package com.elssolution.smartmetrapp;

import com.fazecast.jSerialComm.SerialPort;
import com.serotonin.modbus4j.serial.SerialPortWrapper;

import java.io.InputStream;
import java.io.OutputStream;

public class SerialPortWrapperImpl implements SerialPortWrapper {

    private final String portName;
    private final int baudRate;
    private SerialPort serialPort;

    public SerialPortWrapperImpl(String portName, int baudRate) {
        this.portName = portName;
        this.baudRate = baudRate;
    }

    @Override
    public void close() throws Exception {
        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
        }
    }

    @Override
    public void open() throws Exception {
        serialPort = SerialPort.getCommPort(portName);
        serialPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 1000);

        if (!serialPort.openPort()) {
            throw new Exception("Не вдалося відкрити порт: " + portName);
        }
    }

    @Override
    public InputStream getInputStream() {
        return serialPort.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
        return serialPort.getOutputStream();
    }

    @Override
    public int getBaudRate() {
        return serialPort.getBaudRate();
    }

    @Override
    public int getFlowControlIn() {
        return serialPort.getFlowControlSettings();
    }

    @Override
    public int getFlowControlOut() {
        return serialPort.getFlowControlSettings();
    }

    @Override
    public int getDataBits() {
        return serialPort.getNumDataBits();
    }

    @Override
    public int getStopBits() {
        return serialPort.getNumStopBits();
    }

    @Override
    public int getParity() {
        return serialPort.getParity();
    }
}