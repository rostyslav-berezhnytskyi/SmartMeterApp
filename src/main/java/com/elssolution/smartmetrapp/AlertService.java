package com.elssolution.smartmetrapp;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AlertService {

    public enum Severity { INFO, WARN, ERROR, CRITICAL }

    @Value @Builder
    public static class AlertView {
        String key;
        String message;
        Severity severity;
        long firstSeen;  // epoch ms
        long lastSeen;   // epoch ms
        int count;       // how many times raised while active
        boolean active;
    }

    @Value @Builder
    public static class EventView {
        String key;
        String message;
        Severity severity;
        long ts;         // epoch ms
        String type;     // "RAISE" or "RESOLVE"
    }

    @Value @Builder
    public static class AlertsSnapshot {
        List<AlertView> active;
        List<EventView> recent; // last N events (raise/resolve)
    }

    public interface AlertSink {
        default void onRaise(AlertView a) {}
        default void onResolve(AlertView a) {}
    }

    // ---- state ----
    private final Map<String, MutableAlert> alerts = new ConcurrentHashMap<>();
    private final Deque<EventView> recent = new ArrayDeque<>();
    private final List<AlertSink> sinks = new CopyOnWriteArrayList<>();
    private final int recentCapacity = 50;

    // ---- API ----
    public void registerSink(AlertSink sink) { sinks.add(sink); }

    public void raise(String key, String message, Severity sev) {
        MutableAlert a = alerts.computeIfAbsent(key, k -> new MutableAlert(k, sev, message));
        synchronized (a) {
            a.active = true;
            a.severity = sev;
            a.message = message;
            a.count.incrementAndGet();
            a.lastSeen = System.currentTimeMillis();
        }
        log.warn("ALERT RAISE key={} sev={} msg={}", key, sev, message);
        emitEvent(key, message, sev, "RAISE");
        sinks.forEach(s -> s.onRaise(a.view()));
    }

    public void resolve(String key) {
        MutableAlert a = alerts.get(key);
        if (a == null) return;
        boolean wasActive;
        synchronized (a) {
            wasActive = a.active;
            a.active = false;
            a.lastSeen = System.currentTimeMillis();
        }
        if (wasActive) {
            log.info("ALERT RESOLVE key={}", key);
            emitEvent(key, "recovered", a.severity, "RESOLVE");
            sinks.forEach(s -> s.onResolve(a.view()));
        }
    }

    public AlertsSnapshot snapshot() {
        List<AlertView> active = alerts.values().stream()
                .filter(ma -> ma.active)
                .sorted(Comparator.comparingLong(ma -> -ma.lastSeen))
                .map(MutableAlert::view)
                .toList();
        List<EventView> recentCopy;
        synchronized (recent) {
            recentCopy = new ArrayList<>(recent);
        }
        Collections.reverse(recentCopy); // newest last -> newest first
        return AlertsSnapshot.builder().active(active).recent(recentCopy).build();
    }

    // ---- internals ----
    private void emitEvent(String key, String msg, Severity sev, String type) {
        EventView ev = EventView.builder()
                .key(key).message(msg).severity(sev).type(type)
                .ts(System.currentTimeMillis())
                .build();
        synchronized (recent) {
            recent.addLast(ev);
            while (recent.size() > recentCapacity) recent.removeFirst();
        }
    }

    private static class MutableAlert {
        final String key;
        volatile String message;
        volatile Severity severity;
        volatile boolean active = true;
        final long firstSeen = System.currentTimeMillis();
        volatile long lastSeen = firstSeen;
        final AtomicInteger count = new AtomicInteger(0);

        MutableAlert(String key, Severity severity, String message) {
            this.key = key; this.severity = severity; this.message = message;
        }
        AlertView view() {
            return AlertView.builder()
                    .key(key).message(message).severity(severity).active(active)
                    .firstSeen(firstSeen).lastSeen(lastSeen).count(count.get())
                    .build();
        }
    }
}

