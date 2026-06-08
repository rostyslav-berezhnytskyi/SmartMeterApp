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

    // Make read/write timeouts configurable (defaults match your current behavior).
    @Value("${serial.io.readTimeoutMs:1000}")
    private int readTimeoutMs;
    @Value("${serial.io.writeTimeoutMs:1000}")
    private int writeTimeoutMs;


    private boolean forSlave; // true = RTU slave (respond to polls)
    private final boolean debugRaw; // log raw RX/TX bytes + turnaround timing (diagnostics)
    private SerialPort serialPort;

    // Dedicated logger so raw frames are easy to grep/filter and route to their own file.
    private static final org.slf4j.Logger RAW =
            org.slf4j.LoggerFactory.getLogger("MODBUS_RAW");

    // Shared turnaround tracking: nanoTime when our last TX finished, so the next
    // RX read can report the gap (RS-485 line-turnaround latency).
    private volatile long lastTxEndNanos = 0L;

    public SerialPortWrapperImpl(String portName, int baudRate,
                                 int readTimeoutMs, int writeTimeoutMs, boolean forSlave) {
        this(portName, baudRate, readTimeoutMs, writeTimeoutMs, forSlave, false);
    }

    public SerialPortWrapperImpl(String portName, int baudRate,
                                 int readTimeoutMs, int writeTimeoutMs,
                                 boolean forSlave, boolean debugRaw) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.readTimeoutMs = Math.max(50, readTimeoutMs);
        this.writeTimeoutMs = Math.max(50, writeTimeoutMs);
        this.forSlave = forSlave;
        this.debugRaw = debugRaw;
    }

    @Override
    public void open() throws IOException {
        if (serialPort != null && serialPort.isOpen()) {
            return; // already open
        }

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        // SEMI_BLOCKING for both master and slave: read() returns the instant bytes are
        // available instead of waiting for the buffer to fill or the timeout to expire.
        // On the slave side this is critical — TIMEOUT_READ_BLOCKING batched up to
        // readTimeoutMs (300 ms) of bytes per read, making every poll response ~300 ms
        // late. The inverter then timed out, retransmitted the same request 2-3x, those
        // duplicates piled into one batched read, and modbus4j's length-based RTU framer
        // desynced on the concatenation -> CRC mismatch + dropped responses -> inverter
        // backs its output down (the "stuck at 20 W" hunting).
        int mode = SerialPort.TIMEOUT_READ_SEMI_BLOCKING;
        serialPort.setComPortTimeouts(mode, readTimeoutMs, writeTimeoutMs);

        log.info("serial_open port={} baud={} dataBits=8 stopBits=1 parity=NONE rTimeoutMs={} wTimeoutMs={}",
                portName, baudRate, readTimeoutMs, writeTimeoutMs);

        if (!serialPort.openPort()) {
            log.error("serial_open_failed port={} baud={}", portName, baudRate);
            throw new IOException("Cannot open serial port: " + portName);
        } else {
            // ---- fallback “flush”: drain any junk from RX and give UART a tick to settle ----
            try {
                var in = serialPort.getInputStream();
                byte[] buf = new byte[256];
                long stop = System.currentTimeMillis() + 100; // cap at ~100ms
                while (System.currentTimeMillis() < stop && in.available() > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, Math.max(1, in.available())));
                    if (n <= 0) break;
                }
            } catch (Exception ignore) { /* best-effort drain */ }

            try { serialPort.getOutputStream().flush(); } catch (Exception ignore) { /* may be no-op */ }

            try { Thread.sleep(150); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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

    @Override public InputStream getInputStream()  {
        InputStream in = serialPort.getInputStream();
        return debugRaw ? new LoggingInputStream(in) : in;
    }

    @Override public OutputStream getOutputStream() {
        OutputStream out = serialPort.getOutputStream();
        return debugRaw ? new LoggingOutputStream(out) : out;
    }

    // ===== Diagnostic stream decorators (only used when debugRaw=true) =====

    private static String hex(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = off; i < off + len; i++) {
            sb.append(String.format("%02X ", b[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /** Logs every chunk read from the line as hex, with the gap since the previous
     *  read and (when applicable) the turnaround gap since our last TX finished.
     *  A clipped leading address byte right after a TX points to RS-485 turnaround;
     *  random extra/garbled bytes point to line noise. */
    private final class LoggingInputStream extends InputStream {
        private final InputStream delegate;
        private long lastReadNanos = 0L;

        LoggingInputStream(InputStream delegate) { this.delegate = delegate; }

        @Override public int read() throws IOException {
            int v = delegate.read();
            if (v >= 0) logChunk(new byte[]{(byte) v}, 0, 1);
            return v;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            if (n > 0) logChunk(b, off, n);
            return n;
        }

        private void logChunk(byte[] b, int off, int n) {
            long now = System.nanoTime();
            long gapUs = lastReadNanos == 0 ? -1 : (now - lastReadNanos) / 1000;
            long txGapUs = lastTxEndNanos == 0 ? -1 : (now - lastTxEndNanos) / 1000;
            lastReadNanos = now;
            // txGap is only meaningful for the first read after a TX; large readGap marks a frame boundary
            RAW.info("RX {}B readGapUs={} sinceTxUs={} : {}", n, gapUs, txGapUs, hex(b, off, n));
        }

        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    /** Logs every response frame we transmit and stamps the TX-end time so the next
     *  RX read can measure line turnaround. */
    private final class LoggingOutputStream extends OutputStream {
        private final OutputStream delegate;

        LoggingOutputStream(OutputStream delegate) { this.delegate = delegate; }

        @Override public void write(int b) throws IOException {
            delegate.write(b);
            RAW.info("TX 1B : {}", String.format("%02X", b & 0xFF));
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            RAW.info("TX {}B : {}", len, hex(b, off, len));
        }

        @Override public void flush() throws IOException {
            delegate.flush();
            lastTxEndNanos = System.nanoTime(); // mark turnaround start
        }

        @Override public void close() throws IOException { delegate.close(); }
    }

    @Override public int getBaudRate() {
        return serialPort.getBaudRate();
    }

    // Return explicit flow control (NONE) for both directions.
    @Override public int getFlowControlIn() {
        return SerialPort.FLOW_CONTROL_DISABLED;
    }

    @Override public int getFlowControlOut() {
        return SerialPort.FLOW_CONTROL_DISABLED;
    }

    @Override public int getDataBits() {
        return serialPort.getNumDataBits();
    }

    @Override public int getStopBits() {
        return serialPort.getNumStopBits();
    }

    @Override public int getParity() {
        return serialPort.getParity();
    }
}