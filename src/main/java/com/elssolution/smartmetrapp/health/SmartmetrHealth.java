package com.elssolution.smartmetrapp.health;

import com.elssolution.smartmetrapp.service.StatusService;
import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;

@Component
public class SmartmetrHealth implements HealthIndicator {
    private final StatusService status;

    public SmartmetrHealth(StatusService status) { this.status = status; }

    @Override public Health health() {
        var v = status.buildStatusView();
        boolean ok = "ONLINE".equalsIgnoreCase(String.valueOf(v.getSolisState()))
                && (v.getSmAgeMs() != 0 && v.getSmAgeMs() < 30_000); // meter data fresh

        return (ok ? Health.up() : Health.down())
                .withDetail("solisState", v.getSolisState())
                .withDetail("smAgeMs", v.getSmAgeMs())
                .withDetail("outAgeMs", v.getOutAgeMs())
                .withDetail("gridAgeMs", v.getGridAgeMs())
                .build();
    }
}
