package com.elssolution.smartmetrapp.integration.telegram;

import com.elssolution.smartmetrapp.alerts.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TelegramAlertSink implements AlertService.AlertSink {

    @Value("${alert.telegram.enabled:false}")   private boolean enabled;
    @Value("${alert.telegram.botToken:}")       private String botToken;
    @Value("${alert.telegram.chatIds:}")        private String chatIdsCsv;     // comma-separated
    @Value("${alert.telegram.cooldownMs:900000}") private long cooldownMs;
    @Value("${alert.telegram.prefix:}")         String prefix;                 // <<< device tag

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private List<String> targets = List.of();

    String apiUrl() { return "https://api.telegram.org/bot" + botToken + "/sendMessage"; }

    @PostConstruct
    void init() {
        targets = Arrays.stream(Optional.ofNullable(chatIdsCsv).orElse("")
                        .split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        log.info("Telegram sink registered: enabled={} tokenSet={} targets={}",
                enabled, !botToken.isBlank(), targets);
    }

    @Override public void onRaise(AlertService.AlertView a) {
        if (!enabled || botToken.isBlank() || targets.isEmpty()) return;
        long now = System.currentTimeMillis();
        Long last = lastSent.get(a.getKey());
        if (last != null && (now - last) < cooldownMs) return;

        String hdr = prefix == null || prefix.isBlank() ? "" : "*" + esc(prefix) + "*\n";
        String text = hdr +
                "⚠️ *" + a.getSeverity() + "* `" + esc(a.getKey()) + "`\n" +
                esc(a.getMessage()) + "\n" +
                "_firstSeen:_ " + Instant.ofEpochMilli(a.getFirstSeen()) + "\n" +
                "_lastSeen:_ " + Instant.ofEpochMilli(a.getLastSeen());
        if (sendToAll(text)) lastSent.put(a.getKey(), now);
    }

    @Override public void onResolve(AlertService.AlertView a) {
        if (!enabled || botToken.isBlank() || targets.isEmpty()) return;
        String hdr = prefix == null || prefix.isBlank() ? "" : "*" + esc(prefix) + "*\n";
        String text = hdr +
                "✅ *RECOVERED* `" + esc(a.getKey()) + "`\n" +
                "_lastSeen:_ " + Instant.ofEpochMilli(a.getLastSeen());
        sendToAll(text);
        lastSent.remove(a.getKey());
    }

    /** Public helper for lifecycle pings etc. */
    public boolean sendToAll(String markdownText) {
        boolean ok = true;
        for (String chatId : targets) ok &= sendOne(chatId, markdownText);
        return ok;
    }

    private boolean sendOne(String chatId, String markdownText) {
        try {
            String body = "chat_id=" + url(chatId)
                    + "&parse_mode=Markdown"
                    + "&text=" + url(markdownText);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() / 100 == 2;
            if (!ok) log.warn("Telegram send failed ({}): {}", resp.statusCode(), resp.body());
            else     log.info("Telegram sent to {} ({})", chatId, resp.statusCode());
            return ok;
        } catch (Exception e) {
            log.warn("Telegram send exception to {}: {}", chatId, e.toString());
            return false;
        }
    }

    public boolean sendWithPrefix(String bodyMarkdown) {
        String hdr = (prefix == null || prefix.isBlank()) ? "" : "*" + esc(prefix) + "*\n";
        return sendToAll(hdr + bodyMarkdown);
    }

    private static String url(String s){ return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }
    private static String esc(String s){ return s == null ? "" : s.replace("_","\\_").replace("*","\\*").replace("`","\\`"); }
}
