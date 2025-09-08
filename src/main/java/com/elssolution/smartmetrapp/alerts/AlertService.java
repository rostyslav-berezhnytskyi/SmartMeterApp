package com.elssolution.smartmetrapp.alerts;

import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class AlertService {

    public enum Severity { INFO, WARN, ERROR, CRITICAL }

    // ---- Views returned to callers ----
    @Value @Builder
    public static class AlertView {
        String key;
        String message;
        Severity severity;
        long firstSeen;   // epoch ms (start of the *current* episode)
        long lastSeen;    // epoch ms
        int count;        // how many times raise() was called in this episode
        boolean active;   // true while episode is ongoing
    }

    @Value @Builder
    public static class EventView {
        String key;
        String message;
        Severity severity;
        long ts;          // epoch ms
        String type;      // "RAISE" or "RESOLVE"
    }

    @Value @Builder
    public static class AlertsSnapshot {
        List<AlertView> active;
        List<EventView> recent; // last N events (raise/resolve)
    }

    /** A finished or still-active episode; used for the “deck”. */
    @Value @Builder
    public static class EpisodeView {
        String key;
        String message;
        Severity severity;
        long startedAt;      // when this episode started
        long lastSeen;       // last time we saw it
        Long resolvedAt;     // null if still active
        int count;
        boolean active;
    }

    /** Pluggable sinks — e.g., Telegram. */
    public interface AlertSink {
        default void onRaise(AlertView a) {}
        default void onResolve(AlertView a) {}
    }

    // ---- state ----
    private final Map<String, MutableAlert> alerts = new ConcurrentHashMap<>();
    private final Deque<EventView> recent = new ArrayDeque<>();
    private final List<AlertSink> sinks = new CopyOnWriteArrayList<>();
    private final int recentCapacity = 50;

    // Rolling history of resolved episodes (for the deck)
    private final Deque<EpisodeView> episodeHistory = new ArrayDeque<>();
    private final int episodeHistoryCapacity = 100;
    private final Severity deckMinSeverity = Severity.WARN;               // show WARN+ in the deck

    // ---- API ----
    public void registerSink(AlertSink sink) { sinks.add(sink); }

    /** Raise or refresh an alert. Starts a new episode if it was inactive. */
    public void raise(String key, String message, Severity sev) {
        long now = System.currentTimeMillis();
        MutableAlert a = alerts.computeIfAbsent(key, k -> new MutableAlert(k, sev, message, now));

        boolean startingNewEpisode;
        synchronized (a) {
            startingNewEpisode = !a.active;   //detect transition
            if (startingNewEpisode) {
                // Reset episode counters/timestamps when alert re-appears
                a.firstSeen = now;            //firstSeen is episode start (was final before)
                a.count.set(0);               //reset per-episode count
            }
            a.active   = true;
            a.severity = sev;
            a.message  = message;
            a.count.incrementAndGet();
            a.lastSeen = now;
        }

        log.warn("ALERT RAISE key={} sev={} msg={}", key, sev, message);
        emitEvent(key, message, sev, "RAISE");
        sinks.forEach(s -> s.onRaise(a.view()));
    }

    /** Resolve an alert; closes the episode and stores it into history. */
    public void resolve(String key) {
        MutableAlert a = alerts.get(key);
        if (a == null) return;

        boolean wasActive;
        long now = System.currentTimeMillis();
        Severity sevAtResolve;
        String msgAtResolve;
        long startedAt;
        long lastSeen;
        int count;

        synchronized (a) {
            wasActive = a.active;
            sevAtResolve = a.severity;
            msgAtResolve = a.message;
            startedAt = a.firstSeen;
            lastSeen  = a.lastSeen;
            count     = a.count.get();
            a.active  = false;
            a.lastSeen = now;
        }

        if (wasActive) {
            log.info("ALERT RESOLVE key={}", key);
            emitEvent(key, "recovered", sevAtResolve, "RESOLVE");
            sinks.forEach(s -> s.onResolve(a.view()));

            // Store a compact snapshot of the finished episode (for the deck)
            if (sevAtResolve.ordinal() >= deckMinSeverity.ordinal()) {
                addEpisodeToHistory(EpisodeView.builder()
                        .key(key).message(msgAtResolve).severity(sevAtResolve)
                        .startedAt(startedAt).lastSeen(lastSeen)
                        .resolvedAt(now).count(count).active(false)
                        .build());
            }
        }
    }

    /** Standard snapshot: all active alerts + recent raw events. */
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

    /**
     * The “deck”: newest first, at most 'limit' items.
     * It contains:
     *   1) all currently active WARN+/ERROR/CRITICAL episodes (sorted by lastSeen desc)
     *   2) then the most recent resolved episodes from history (no duplicates)
     */
    public List<EpisodeView> lastErrorDeck(int limit) {
        int cap = Math.max(1, Math.min(limit, 50));
        List<EpisodeView> out = new ArrayList<>(cap);

        // 1) Active episodes (qualifying severities), newest first
        alerts.values().stream()
                .filter(a -> a.active && a.severity.ordinal() >= deckMinSeverity.ordinal())
                .sorted(Comparator.comparingLong((MutableAlert a) -> a.lastSeen).reversed())
                .forEach(a -> {
                    if (out.size() < cap) {
                        out.add(EpisodeView.builder()
                                .key(a.key).message(a.message).severity(a.severity)
                                .startedAt(a.firstSeen).lastSeen(a.lastSeen)
                                .resolvedAt(null).count(a.count.get()).active(true)
                                .build());
                    }
                });

        // 2) Fill the rest from resolved history, newest first, avoid duplicates
        synchronized (episodeHistory) {
            Iterator<EpisodeView> it = episodeHistory.descendingIterator();
            while (out.size() < cap && it.hasNext()) {
                EpisodeView ep = it.next();
                boolean dup = out.stream().anyMatch(x ->
                        x.key.equals(ep.key) && x.startedAt == ep.startedAt);
                if (!dup) out.add(ep);
            }
        }
        return out;
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

    private void addEpisodeToHistory(EpisodeView ep) {
        synchronized (episodeHistory) {
            episodeHistory.addLast(ep);
            while (episodeHistory.size() > episodeHistoryCapacity) {
                episodeHistory.removeFirst();
            }
        }
    }

    // ---- per-alert mutable record ----
    private static class MutableAlert {
        final String key;
        volatile String message;
        volatile Severity severity;
        volatile boolean active;
        volatile long firstSeen;        //no longer final; is per-episode start
        volatile long lastSeen;
        final AtomicInteger count = new AtomicInteger(0);

        MutableAlert(String key, Severity severity, String message, long now) {
            this.key = key;
            this.severity = severity;
            this.message = message;
            this.active = true;
            this.firstSeen = now;
            this.lastSeen = now;
            this.count.set(0);
        }

        AlertView view() {
            return AlertView.builder()
                    .key(key).message(message).severity(severity).active(active)
                    .firstSeen(firstSeen).lastSeen(lastSeen).count(count.get())
                    .build();
        }
    }

    // inside AlertService

    @Value @Builder
    public static class DeckItem {
        String key;
        String message;
        Severity severity;
        boolean active;     // true if last event was a RAISE; false if RESOLVE
        long firstTs;       // first time in the burst (epoch ms)
        long lastTs;        // last time in the burst (epoch ms)
        int count;          // how many identical events collapsed into this one
    }

    /**
     * Returns the most-recent event, collapsing a burst of identical events
     * (same key/sev/message/type) that happened within 'gapMs' of each other.
     * If there are no events yet, returns null.
     */
    public DeckItem latestDeckItem(long gapMs) {
        EventView tail;
        synchronized (recent) {
            if (recent.isEmpty()) return null;

            // iterate newest -> older without copying
            var it = recent.descendingIterator();
            tail = it.next(); // newest

            String k = tail.getKey();
            String msg = tail.getMessage();
            Severity sev = tail.getSeverity();
            String type = tail.getType();   // "RAISE" or "RESOLVE"
            long first = tail.getTs();
            long lastT = tail.getTs();
            int cnt = 1;

            while (it.hasNext()) {
                EventView ev = it.next();
                // stop when any attribute differs or time gap exceeds the window
                if (!Objects.equals(ev.getKey(), k)
                        || !Objects.equals(ev.getMessage(), msg)
                        || ev.getSeverity() != sev
                        || !Objects.equals(ev.getType(), type)
                        || (lastT - ev.getTs()) > gapMs) {
                    break;
                }
                first = ev.getTs();
                cnt++;
            }

            boolean active = "RAISE".equalsIgnoreCase(type);
            return DeckItem.builder()
                    .key(k).message(msg).severity(sev)
                    .active(active).firstTs(first).lastTs(lastT).count(cnt)
                    .build();
        }
    }
}
