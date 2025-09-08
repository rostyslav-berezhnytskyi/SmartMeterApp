package com.elssolution.smartmetrapp.alerts;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalUncaughtHandler implements Thread.UncaughtExceptionHandler {

    private final AlertService alerts;
    private final ApplicationEventPublisher publisher;

    private volatile boolean stopping = false; // mute noise while shutting down

    @PostConstruct
    void registerAsDefault() {
        Thread.setDefaultUncaughtExceptionHandler(this);
        log.info("Global uncaught handler installed");
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent e) {
        stopping = true;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (stopping) return; // don't spam during shutdown

        String key = classify(t, e);
        log.error("Uncaught in {} -> {}", t.getName(), e.toString(), e);
        alerts.raise(key, e.toString(), AlertService.Severity.CRITICAL);

        // If a modbus library thread died, tell listeners to reset their masters
        if ("MODBUS_UNCAUGHT".equals(key)) {
            publisher.publishEvent(new ModbusCrashedEvent(e));
        }
    }

    private String classify(Thread t, Throwable e) {
        String name = (t.getName() == null ? "" : t.getName());
        if (name.contains("InputStreamListener") || isModbus(e) ||
                name.contains("Modbus RTU master") || name.contains("Modbus RTU slave")) {
            return "MODBUS_UNCAUGHT";
        }
        return "UNCAUGHT";
    }
    private boolean isModbus(Throwable e) {
        for (StackTraceElement st : e.getStackTrace()) {
            if (st.getClassName().startsWith("com.serotonin.modbus4j")) return true;
        }
        return false;
    }
}


