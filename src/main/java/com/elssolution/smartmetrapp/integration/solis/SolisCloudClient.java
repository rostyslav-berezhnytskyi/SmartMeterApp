package com.elssolution.smartmetrapp.integration.solis;

import com.elssolution.smartmetrapp.alerts.AlertService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP client for SolisCloud: fetches raw inverter detail.
 * Focuses on robust networking + clear alerts. No business rules here.
 */
@Slf4j
@Service
public class SolisCloudClient {

    // ----- Config -----
    @Value("${solis.api.id}")     private String apiId;
    @Value("${solis.api.secret}") private String apiSecret;
    @Value("${solis.api.uri}")    private String solisBaseUri; // e.g. https://www.soliscloud.com
    @Value("${solis.api.sn}")     private String inverterSn;

    /** Per-request timeout (ms). */
    @Value("${solis.http.requestTimeoutMs:6000}")
    private int requestTimeoutMs;

    /** If server/client time drift exceeds this, raise SOLIS_CLOCK_SKEW (ms). */
    @Value("${solis.maxClockSkewMs:90000}")
    private long maxClockSkewMs;

    // ----- HTTP + JSON -----
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            // (connectTimeout is best-effort; we also use per-request .timeout)
            .connectTimeout(Duration.ofSeconds(4))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AlertService alerts;

    // ----- API constants -----
    private static final String PATH_INVERTER_DETAIL = "/v1/api/inverterDetail";
    private static final String CONTENT_TYPE_JSON = "application/json";

    // minimal retry/backoff (+ jitter)
    private static final int[] RETRY_DELAYS_MS = {500, 1000};

    public SolisCloudClient(AlertService alerts) {
        this.alerts = alerts;
    }

    public Optional<SolisDetail> fetchInverterDetailRich() {
        try {
            String bodyJson = "{\"sn\":\"" + inverterSn + "\"}";
            Optional<String> bodyOpt = postJsonWithRetry(PATH_INVERTER_DETAIL, bodyJson);
            if (bodyOpt.isEmpty()) {
                // specific alerts have already been raised inside postJsonWithRetry()
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(bodyOpt.get());
            String apiCode = root.path("code").asText("");
            if (!"0".equals(apiCode)) {
                alerts.raise("SOLIS_DOWN",
                        "API code " + apiCode + " msg=" + root.path("msg").asText(""),
                        AlertService.Severity.WARN);
                return Optional.empty();
            }

            JsonNode d = root.path("data");
            if (d.isMissingNode() || !d.isObject()) {
                alerts.raise("SOLIS_DOWN", "Response missing 'data' object", AlertService.Severity.WARN);
                return Optional.empty();
            }

            long nowMs = System.currentTimeMillis();

            // Required
            Double psum = nodeNum(d, "psum"); // + export, - import
            if (psum == null) {
                alerts.raise("SOLIS_DOWN", "Missing psum in response", AlertService.Severity.WARN);
                return Optional.empty();
            }
            double psumKw = psum;

            // PV power (kW): prefer pac, else dcPac, else powTotal/sum(pow1..32), else dcAcPower(W)
            Double pacKw = nodeNum(d, "pac");
            Double pvKw  = choosePvKw(d, pacKw);

            // Site load (kW): physical balance vs API fields (pick close one)
            Double familyApiKw = readWithUnitKw(d, "familyLoadPower", "familyLoadPowerStr");
            Double totalApiKw  = readWithUnitKw(d, "totalLoadPower",  "totalLoadPowerStr");
            double calcLoadKw  = (pvKw != null ? pvKw : 0.0)
                    + (psumKw < 0 ? -psumKw : 0.0)
                    - (psumKw > 0 ?  psumKw : 0.0);
            Double familyLoadKw = pickPlausibleLoad(familyApiKw, totalApiKw, calcLoadKw);

            Integer state = d.path("state").isMissingNode() ? null : d.path("state").asInt();
            Integer warningInfoData = d.path("warningInfoData").isMissingNode() ? null : d.path("warningInfoData").asInt();

            if (log.isDebugEnabled()) {
                log.debug("Solis rich: psum={}kW, pac={}kW, pv={}kW, load={}kW, state={}, warn={}",
                        psumKw, pacKw, pvKw, familyLoadKw, state, warningInfoData);
                log.debug("PV choose detail: pac={} dcPac={} sumPow={} dcAc={} → pv={}",
                        pacKw, readWithUnitKw(d,"dcPac","dcPacStr"), sumPowStringsKw(d),
                        nodeNum(d,"dcAcPower"), pvKw);
            }

            alerts.resolve("SOLIS_DOWN");
            alerts.resolve("SOLIS_AUTH");
            alerts.resolve("SOLIS_RATE_LIMIT");
            // (CLOCK_SKEW is resolved opportunistically below if skew is small)

            return Optional.of(new SolisDetail(
                    psumKw,
                    pacKw,
                    pvKw,          // NOTE: this may be AC pac; record field name kept for compatibility
                    familyLoadKw,
                    state,
                    warningInfoData,
                    nowMs));

        } catch (Exception ex) {
            alerts.raise("SOLIS_DOWN", "Exception: " + ex.getMessage(), AlertService.Severity.ERROR);
            return Optional.empty();
        }
    }

    /**
     * POST JSON with HMAC headers to Solis. On HTTP errors we classify alerts.
     * Returns body only on HTTP 200.
     */
    private Optional<String> postJsonWithRetry(String path, String bodyJson) {
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            try {
                String contentMd5 = md5Base64(bodyJson);
                String dateHeader = httpDateGmt();
                String toSign = String.join("\n", "POST", contentMd5, CONTENT_TYPE_JSON, dateHeader, path);
                String signature = signHmacSha1(toSign, apiSecret);
                String authHeader = "API " + apiId + ":" + signature;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(safeJoin(solisBaseUri, path)))
                        .header("Accept", CONTENT_TYPE_JSON)
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .header("Content-MD5", contentMd5)
                        .header("Date", dateHeader)
                        .header("Authorization", authHeader)
                        .header("User-Agent", "SmartMetrApp/1.0 (+solis)")
                        .timeout(Duration.ofMillis(Math.max(1000, requestTimeoutMs)))
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();

                // clock skew hint (if server supplies Date)
                resp.headers().firstValue("Date").ifPresent(serverDate -> {
                    try {
                        long serverMs = ZonedDateTime.parse(serverDate, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant().toEpochMilli();
                        long skewMs = Math.abs(serverMs - System.currentTimeMillis());
                        if (skewMs > Math.max(0, maxClockSkewMs)) {
                            alerts.raise("SOLIS_CLOCK_SKEW",
                                    "Local time off by ~" + skewMs + " ms — check NTP",
                                    AlertService.Severity.WARN);
                        } else {
                            alerts.resolve("SOLIS_CLOCK_SKEW");
                        }
                    } catch (Exception ignore) { /* header absent or unparsable */ }
                });

                if (sc == 200) return Optional.ofNullable(resp.body());

                // classify
                if (sc == 401 || sc == 403) {
                    alerts.raise("SOLIS_AUTH", "HTTP " + sc + " — check API id/secret/Date", AlertService.Severity.ERROR);
                } else if (sc == 429) {
                    alerts.raise("SOLIS_RATE_LIMIT", "HTTP 429 — rate limited by Solis", AlertService.Severity.WARN);
                } else if (sc >= 500 && sc < 600) {
                    alerts.raise("SOLIS_DOWN", "HTTP " + sc + " — server error", AlertService.Severity.WARN);
                } else {
                    alerts.raise("SOLIS_DOWN", "HTTP " + sc + " — " + truncate(resp.body(), 240), AlertService.Severity.WARN);
                }

                // retry policy
                if ((sc == 429 || (sc >= 500 && sc < 600)) && attempt < RETRY_DELAYS_MS.length) {
                    int base = RETRY_DELAYS_MS[attempt];
                    int jitter = ThreadLocalRandom.current().nextInt(80, 180);
                    int sleepMs = base + jitter;
                    if (log.isWarnEnabled()) {
                        log.warn("Solis HTTP {} — retrying in {} ms (attempt {}/{})",
                                sc, sleepMs, attempt + 1, RETRY_DELAYS_MS.length);
                    }
                    Thread.sleep(sleepMs);
                    continue;
                }
                // non-retryable
                return Optional.empty();

            } catch (java.net.http.HttpTimeoutException e) {
                // falls under request .timeout()
                alerts.raise("SOLIS_DOWN", "HTTP timeout: " + e.getMessage(), AlertService.Severity.WARN);
                if (attempt < RETRY_DELAYS_MS.length) {
                    sleepQuiet(RETRY_DELAYS_MS[attempt]);
                    continue;
                }
                return Optional.empty();

            } catch (java.io.IOException e) {
                alerts.raise("SOLIS_DOWN", "I/O error: " + e.getMessage(), AlertService.Severity.WARN);
                if (attempt < RETRY_DELAYS_MS.length) {
                    sleepQuiet(RETRY_DELAYS_MS[attempt]);
                    continue;
                }
                return Optional.empty();

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();

            } catch (Exception fatal) {
                alerts.raise("SOLIS_DOWN", "Fatal error: " + fatal.getMessage(), AlertService.Severity.ERROR);
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    // ===== Helpers =====

    private static boolean isNumericText(String s) {
        if (s == null || s.isBlank()) return false;
        try { Double.parseDouble(s.trim()); return true; } catch (Exception e) { return false; }
    }

    /** Reads a numeric field; handles numbers-as-strings too. */
    private static Double nodeNum(JsonNode obj, String field) {
        JsonNode n = obj.path(field);
        if (n.isNumber()) return n.asDouble();
        if (n.isTextual() && isNumericText(n.asText())) return Double.parseDouble(n.asText().trim());
        return null;
    }

    // Read a value and convert to kW if paired unit field says "W"
    private static Double readWithUnitKw(JsonNode d, String valueField, String unitField) {
        Double v = nodeNum(d, valueField);
        if (v == null) return null;
        String unit = d.path(unitField).asText("");
        if ("W".equalsIgnoreCase(unit)) v /= 1000.0;
        return v;
    }

    // PV per-string sum: uses powTotal (W) if present, else sum pow1..pow32 (or Pow1..Pow32), then → kW
    private static Double sumPowStringsKw(JsonNode d) {
        Double totW = nodeNum(d, "powTotal");
        if (totW != null) return totW / 1000.0;

        double sumW = 0;
        boolean any = false;
        for (int i = 1; i <= 32; i++) {
            Double a = nodeNum(d, "pow" + i);
            if (a != null) { sumW += a; any = true; continue; }
            Double b = nodeNum(d, "Pow" + i);
            if (b != null) { sumW += b; any = true; }
        }
        return any ? sumW / 1000.0 : null;
    }

    // Treat "near zero" PV as missing; Solis sometimes reports 0.0 on one field while others are valid.
    private static final double PV_MIN_VALID_KW = 0.05;   // 50 W

    /** Choose the best PV power (kW). Prefer pac; otherwise dcPac (unit-aware); then powTotal/sum(pow1..32); then dcAcPower (W). */
    private static Double choosePvKw(JsonNode d, Double pacKw) {
        Double dcPacKw    = readWithUnitKw(d, "dcPac", "dcPacStr");
        Double powSumKw   = sumPowStringsKw(d);
        Double dcAcPowerW = nodeNum(d, "dcAcPower");
        Double dcAcKw     = (dcAcPowerW != null ? dcAcPowerW / 1000.0 : null);

        if (pacKw   != null && pacKw   > PV_MIN_VALID_KW) return pacKw;
        if (dcPacKw != null && dcPacKw > PV_MIN_VALID_KW) return dcPacKw;
        if (powSumKw!= null && powSumKw> PV_MIN_VALID_KW) return powSumKw;
        if (dcAcKw  != null && dcAcKw  > PV_MIN_VALID_KW) return dcAcKw;

        if (pacKw   != null) return pacKw;
        if (dcPacKw != null) return dcPacKw;
        if (powSumKw!= null) return powSumKw;
        return dcAcKw;
    }

    /** Pick API load if it’s close to the physical balance; otherwise trust the balance. */
    private static Double pickPlausibleLoad(Double familyApiKw, Double totalApiKw, double computedKw) {
        double tol = Math.max(0.6, Math.abs(computedKw) * 0.35);
        if (familyApiKw != null && Math.abs(familyApiKw - computedKw) <= tol) return familyApiKw;
        if (totalApiKw  != null && Math.abs(totalApiKw  - computedKw) <= tol) return totalApiKw;
        return computedKw;
    }

    /** MD5(body) in Base64, as required by Solis API. */
    private String md5Base64(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    /** RFC-1123 GMT date for the Date header. */
    private String httpDateGmt() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    /** HMAC-SHA1 signature for the canonical string, then Base64. */
    private String signHmacSha1(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String truncate(String s, int limit) {
        if (s == null) return "";
        return s.length() <= limit ? s : s.substring(0, Math.max(0, limit)) + "…";
    }

    private static String safeJoin(String base, String path) {
        if (base == null) return path;
        if (base.endsWith("/") && path.startsWith("/")) return base.substring(0, base.length()-1) + path;
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(Math.max(50, Math.min(ms, 4000))); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * NOTE: third field 'dcPacKw' actually carries the chosen PV power (may be AC pac).
     * Kept name for compatibility with existing code.
     */
    public record SolisDetail(
            double psumKw,
            Double pacKw,
            Double dcPacKw,          // chosen PV power (kW), not strictly "DC"
            Double familyLoadKw,
            Integer state,
            Integer warningInfoData,
            long fetchedAtMs
    ) {}
}
