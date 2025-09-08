package com.elssolution.smartmetrapp.alerts;

import com.elssolution.smartmetrapp.integration.telegram.TelegramAlertSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import jakarta.annotation.PreDestroy;

@Component
public class LifecycleNotifier {
    private final TelegramAlertSink sink;
    @Value("${alert.telegram.startupPing:true}")  boolean startupPing;
    @Value("${alert.telegram.shutdownPing:true}") boolean shutdownPing;

    public LifecycleNotifier(TelegramAlertSink sink) { this.sink = sink; }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (startupPing) sink.sendWithPrefix("✅ *STARTED* — " + java.time.Instant.now());
    }

    @PreDestroy
    public void onShutdown() {
        if (shutdownPing) sink.sendWithPrefix("🛑 *STOPPING* — " + java.time.Instant.now());
    }
}

