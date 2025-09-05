package com.elssolution.smartmetrapp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TelegramAlertSink implements AlertService.AlertSink {

    @Value("${alert.telegram.enabled:false}") private boolean enabled;
    @Value("${alert.telegram.botToken:}")    private String botToken;
    @Value("${alert.telegram.chatId:}")      private String chatId;
    @Value("${alert.telegram.cooldownMs:900000}") private long cooldownMs; // 15 min

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();

    private String apiUrl() { return "https://api.telegram.org/bot" + botToken + "/sendMessage"; }

    @Override public void onRaise(AlertService.AlertView a) {
        if (!enabled || botToken.isBlank() || chatId.isBlank()) return;
        long now = System.currentTimeMillis();
        Long last = lastSent.get(a.getKey());
        if (last != null && (now - last) < cooldownMs) return; // cool-down
        String text = "\u26A0\uFE0F *" + a.getSeverity() + "* `" + a.getKey() + "`\n" +
                a.getMessage() + "\n" +
                "_firstSeen:_ " + Instant.ofEpochMilli(a.getFirstSeen()) + "\n" +
                "_lastSeen:_ " + Instant.ofEpochMilli(a.getLastSeen());
        if (send(text)) lastSent.put(a.getKey(), now);
    }

    @Override public void onResolve(AlertService.AlertView a) {
        if (!enabled || botToken.isBlank() || chatId.isBlank()) return;
        String text = "\u2705 *RECOVERED* `" + a.getKey() + "`\n" +
                "_lastSeen:_ " + Instant.ofEpochMilli(a.getLastSeen());
        send(text);
        lastSent.remove(a.getKey());
    }

    private boolean send(String markdownText) {
        try {
            String body = "chat_id=" + chatId + "&parse_mode=Markdown&text=" + urlEncode(markdownText);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("Telegram send failed: {} {}", resp.statusCode(), resp.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Telegram send exception: {}", e.toString());
            return false;
        }
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }
}

