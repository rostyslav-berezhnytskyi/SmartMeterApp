package com.elssolution.smartmetrapp;

import com.elssolution.smartmetrapp.alerts.AlertHeartbeat;
import com.elssolution.smartmetrapp.domain.SmSnapshot;
import com.elssolution.smartmetrapp.integration.modbus.ModbusInverterFeeder;
import com.elssolution.smartmetrapp.integration.modbus.ModbusSmReader;
import com.elssolution.smartmetrapp.integration.solis.SolisCloudClient;
import com.elssolution.smartmetrapp.integration.telegram.TelegramAlertSink;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Force Acrel mode and provide dummy Solis creds so placeholders resolve
                "smartmetr.kind=acrel",
                "solis.api.id=dummy",
                "solis.api.secret=dummy",
                "solis.api.sn=dummy",
                "solis.api.uri=http://localhost",

                // Keep background jobs quiet in tests
                "solis.fetch.periodSeconds=3600",
                "alert.telegram.heartbeat.enabled=false",

                // Bind UI to random port only
                "server.port=0"
        }
)
class SmokeTest {

    @LocalServerPort int port;

    @Autowired TestRestTemplate http;

    // Mocks for anything scheduling or I/O
    @MockitoBean ScheduledExecutorService scheduler;
    @MockitoBean SolisCloudClient solis;
    @MockitoBean ModbusSmReader smReader;
    @MockitoBean ModbusInverterFeeder feeder;
    @MockitoBean TelegramAlertSink telegram;
    @MockitoBean AlertHeartbeat alertHeartbeat;

    @Test
    void status_endpoint_returns_200() {
        // No Solis data → service should treat as stale and return delta 0.0
        when(solis.fetchInverterDetailRich()).thenReturn(Optional.empty());

        // Provide a harmless empty snapshot so StatusService won’t NPE on a mock
        when(smReader.getLatestSnapshotSM()).thenReturn(new SmSnapshot(new short[400], 0L));

        var resp = http.getForEntity("http://localhost:" + port + "/status", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("gridAgeMs");
    }
}

