package com.elssolution.smartmetrapp.alerts;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.OutputStream;
import java.io.PrintStream;

@Component
public class ModbusErrInterceptor {

    private final ApplicationEventPublisher publisher;
    private int consecutiveErrors = 0;
    private long lastErrorTime = 0;

    public ModbusErrInterceptor(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostConstruct
    public void init() {
        PrintStream originalErr = System.err;
        // Перехоплюємо стандартний потік помилок
        System.setErr(new PrintStream(new OutputStream() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public synchronized void write(int b) {
                originalErr.write(b); // обов'язково зберігаємо вивід на екран/в журнал
                char c = (char) b;
                buffer.append(c);
                if (c == '\n') {
                    checkLine(buffer.toString());
                    buffer.setLength(0);
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                originalErr.write(b, off, len);
                String str = new String(b, off, len);
                buffer.append(str);
                int newlineIdx;
                while ((newlineIdx = buffer.indexOf("\n")) != -1) {
                    checkLine(buffer.substring(0, newlineIdx + 1));
                    buffer.delete(0, newlineIdx + 1);
                }
            }
        }, true));
    }

    private void checkLine(String line) {
        if (line.contains("CRC mismatch") || line.contains("wha") || line.contains("ModbusTransportException")) {
            long now = System.currentTimeMillis();
            if (now - lastErrorTime > 2000) {
                consecutiveErrors = 0;
            }
            lastErrorTime = now;
            consecutiveErrors++;

            // Якщо зловили 5 помилок поспіль - смикаємо рубильник
            if (consecutiveErrors >= 5) {
                consecutiveErrors = 0;
                publisher.publishEvent(new ModbusCrashedEvent(new RuntimeException("Intercepted Modbus error stream from System.err")));
            }
        }
    }
}
