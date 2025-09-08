package com.elssolution.smartmetrapp.web;

import com.elssolution.smartmetrapp.alerts.AlertService;
import com.elssolution.smartmetrapp.service.StatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class StatusController {

    private final AlertService alerts;

    private final StatusService status;

    public StatusController(AlertService alerts, StatusService status) {
        this.alerts = alerts;
        this.status = status;
    }

    @GetMapping("/status")
    public StatusService.StatusView getStatus() {
        return status.buildStatusView();
    }

    @GetMapping({"/uiStatus"})
    public ResponseEntity<Void> uiStatus() {
        return ResponseEntity.status(HttpStatus.FOUND) // 302
                .location(URI.create("/status.html"))
                .build();
    }

    @GetMapping("/alerts")
    public AlertService.AlertsSnapshot getAlerts() {
        return alerts.snapshot();
    }

    @GetMapping("/alerts/deck")
    public java.util.List<AlertService.EpisodeView> getAlertDeck(
            @org.springframework.web.bind.annotation.RequestParam(name = "limit", defaultValue = "10") int limit) {
        return alerts.lastErrorDeck(limit);
    }

    @GetMapping("/alerts/last")
    public ResponseEntity<AlertService.DeckItem> lastAlert() {
        AlertService.DeckItem d = alerts.latestDeckItem(5000); // collapse window = 5s
        return (d == null) ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(d);
    }
}
