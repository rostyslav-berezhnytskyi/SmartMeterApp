package com.elssolution.smartmetrapp.integration.modbus;

import com.fazecast.jSerialComm.SerialPort;
import com.serotonin.modbus4j.serial.SerialPortWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Slf4j
public class SerialPortWrapperImpl implements SerialPortWrapper {

    private final String portName;
    private final int baudRate;
    private final int readTimeoutMs;
    private final int writeTimeoutMs;
    private boolean forSlave; // true = RTU slave (respond to polls)
    private SerialPort serialPort;

    public SerialPortWrapperImpl(String portName, int baudRate,
                                 int readTimeoutMs, int writeTimeoutMs, boolean forSlave) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.forSlave = forSlave;

        // IMPORTANT:
        // - Slave must be non-blocking (0/0).
        // - Master can have sane timeouts; keep a floor to avoid 0.
        if (forSlave) {
            this.readTimeoutMs = 0;
            this.writeTimeoutMs = 0;
        } else {
            this.readTimeoutMs = Math.max(50, readTimeoutMs);
            this.writeTimeoutMs = Math.max(50, writeTimeoutMs);
        }
    }

    @Override
    public void open() throws IOException {
        if (serialPort != null && serialPort.isOpen()) {
            return; // already open
        }

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        int mode = forSlave
                ? SerialPort.TIMEOUT_NONBLOCKING
                : SerialPort.TIMEOUT_READ_SEMI_BLOCKING;
        serialPort.setComPortTimeouts(mode, readTimeoutMs, writeTimeoutMs);

        log.info("serial_open port={} baud={} dataBits=8 stopBits=1 parity=NONE rTimeoutMs={} wTimeoutMs={}",
                portName, baudRate, readTimeoutMs, writeTimeoutMs);

        if (!serialPort.openPort()) {
            log.error("serial_open_failed port={} baud={}", portName, baudRate);
            throw new IOException("Cannot open serial port: " + portName);
        }
        // Small best-effort RX drain; keep it, but it won’t block the slave
        try (var in = serialPort.getInputStream()) {
            byte[] buf = new byte[256];
            long stop = System.currentTimeMillis() + 100;
            while (System.currentTimeMillis() < stop && in.available() > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, Math.max(1, in.available())));
                if (n <= 0) break;
            }
        } catch (Exception ignore) {
        }

        try {
            serialPort.getOutputStream().flush();
        } catch (Exception ignore) { /* may be no-op */ }

        try {
            Thread.sleep(150);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }


    @Override
    public void close() {
        if (serialPort != null && serialPort.isOpen()) {
            try {
                serialPort.closePort();
                log.info("serial_closed port={}", portName);
            } catch (Exception ignore) {
                // swallow — closing on shutdown shouldn’t blow up the app
            }
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

    // Return explicit flow control (NONE) for both directions.
    @Override
    public int getFlowControlIn() {
        return SerialPort.FLOW_CONTROL_DISABLED;
    }

    @Override
    public int getFlowControlOut() {
        return SerialPort.FLOW_CONTROL_DISABLED;
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