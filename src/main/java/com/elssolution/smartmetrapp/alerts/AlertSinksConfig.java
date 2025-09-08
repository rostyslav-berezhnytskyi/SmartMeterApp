package com.elssolution.smartmetrapp.alerts;

import com.elssolution.smartmetrapp.integration.telegram.TelegramAlertSink;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class AlertSinksConfig {
    private final AlertService alertService;
    private final TelegramAlertSink telegramAlertSink;

    @PostConstruct
    void init() {
        alertService.registerSink(telegramAlertSink);
    }
}
