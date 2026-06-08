package com.elssolution.smartmetrapp.alerts;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Redirects modbus4j's System.err noise (CRC mismatches, ShouldNeverHappenException)
 * to SLF4J so it appears in app.log rather than stderr.log.
 *
 * CRC errors on the RTU slave receive side are normal RS-485 line noise.
 * modbus4j's InputStreamListener catches them internally and continues — the
 * slave thread does NOT die, no port restart is needed.  We only log a
 * throttled warning so the noise is visible without flooding the log.
 */
@Slf4j
@Component
public class ModbusErrInterceptor {

    private volatile long lastLoggedAt = 0;

    // ---- diagnostics: true CRC-error rate (the sample log above is throttled to 1/10s) ----
    private volatile long crcCount = 0;        // total CRC mismatches seen
    private volatile long crcWindowStart = 0;  // start of current reporting window
    private volatile long crcWindowCount = 0;  // CRC mismatches in current window

    @PostConstruct
    public void init() {
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public synchronized void write(int b) {
                originalErr.write(b);
                char c = (char) b;
                buffer.append(c);
                if (c == '\n') {
                    maybeLog(buffer.toString());
                    buffer.setLength(0);
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                originalErr.write(b, off, len);
                String str = new String(b, off, len);
                buffer.append(str);
                int idx;
                while ((idx = buffer.indexOf("\n")) != -1) {
                    maybeLog(buffer.substring(0, idx + 1));
                    buffer.delete(0, idx + 1);
                }
            }
        }, true));
    }

    private void maybeLog(String line) {
        boolean isCrc = line.contains("CRC mismatch");
        if (!isCrc && !line.contains("wha") && !line.contains("ModbusTransportException")) return;

        long now = System.currentTimeMillis();

        // accurate rate accounting (independent of the throttled sample below)
        if (isCrc) {
            crcCount++;
            crcWindowCount++;
            if (crcWindowStart == 0) crcWindowStart = now;
            long windowMs = now - crcWindowStart;
            if (windowMs >= 5_000) {
                double perSec = crcWindowCount * 1000.0 / windowMs;
                log.warn("modbus_crc_rate: {} errors in {} ms ({} /s), total={}",
                        crcWindowCount, windowMs, String.format("%.1f", perSec), crcCount);
                crcWindowStart = now;
                crcWindowCount = 0;
            }
        }

        if (now - lastLoggedAt > 10_000) {
            log.warn("modbus_rs485_noise (slave rx sample): {}", line.trim());
            lastLoggedAt = now;
        }
    }
}