package com.elssolution.smartmetrapp;

import com.elssolution.smartmetrapp.integration.telegram.TelegramAlertSink;
import com.elssolution.smartmetrapp.domain.MeterDecoder;
import com.elssolution.smartmetrapp.domain.MeterRegisterMap;
import com.elssolution.smartmetrapp.integration.modbus.ModbusInverterFeeder;
import com.elssolution.smartmetrapp.integration.modbus.ModbusSmReader;
import com.elssolution.smartmetrapp.integration.solis.SolisCloudClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.elssolution.smartmetrapp.alerts.AlertHeartbeat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // dummy Solis creds so placeholders always resolve
                "solis.api.id=dummy",
                "solis.api.secret=dummy",
                "solis.api.sn=dummy",
                "solis.api.uri=http://localhost",

                // keep external stuff quiet in tests
                "solis.fetch.periodSeconds=3600",
                "alert.telegram.heartbeat.enabled=false",

                // bind UI to random port only
                "server.port=0"
        }
)
class SmokeTest {
    @LocalServerPort int port;

    @Autowired TestRestTemplate http;

    @MockitoBean ScheduledExecutorService scheduler;
    @MockitoBean SolisCloudClient solis;
    @MockitoBean ModbusSmReader smReader;
    @MockitoBean ModbusInverterFeeder feeder;
    @MockitoBean MeterDecoder codec;
    @MockitoBean MeterRegisterMap map;
    @MockitoBean TelegramAlertSink telegram;
    @MockitoBean AlertHeartbeat alertHeartbeat;

    @Test
    void status_endpoint_returns_200() {
        when(solis.fetchInverterDetailRich()).thenReturn(Optional.empty());

        var resp = http.getForEntity("http://localhost:" + port + "/status", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("gridAgeMs");
    }
}
