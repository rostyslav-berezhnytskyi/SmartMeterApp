package com.elssolution.smartmetrapp.alerts;

import com.elssolution.smartmetrapp.integration.telegram.TelegramAlertSink;
import com.elssolution.smartmetrapp.service.StatusService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AlertHeartbeat {

    private final TelegramAlertSink sink;
    private final StatusService status;

    @Value("${alert.telegram.heartbeat.enabled:true}")
    boolean enabled;

    public AlertHeartbeat(TelegramAlertSink sink, StatusService status) {
        this.sink = sink; this.status = status;
    }

    // One ping per day (time is configurable with the property below)
    @Scheduled(cron = "${alert.telegram.heartbeat.cron:0 0 10 * * *}")
    public void dailyPing() {
        if (!enabled) return;

        var v = status.buildStatusView(); // you already expose these fields in /status
        boolean up = "ONLINE".equalsIgnoreCase(String.valueOf(v.getSolisState()))
                && v.getSmAgeMs() != 0 && v.getSmAgeMs() < 30_000;

        String msg = "*HEARTBEAT* — " + (up ? "UP" : "DEGRADED") + "\n" +
                "_solis:_ " + v.getSolisState() + "\n" +
                "_smAge:_ " + (v.getSmAgeMs() == 0 ? "-" : v.getSmAgeMs() + " ms") + "\n" +
                "_gridAge:_ " + (v.getGridAgeMs() == 0 ? "-" : v.getGridAgeMs() + " ms");

        sink.sendWithPrefix(msg);
    }
}

