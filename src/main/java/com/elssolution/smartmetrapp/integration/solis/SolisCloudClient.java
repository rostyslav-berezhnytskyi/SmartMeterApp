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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * HTTP client for SolisCloud: fetches the *raw* inverter detail.
 * No business rules here — it only returns what the API says.
 */
@Slf4j
@Service
public class SolisCloudClient {

    // ----- Config from application.yml / .env -----
    @Value("${solis.api.id}")
    private String apiId;

    @Value("${solis.api.secret}")
    private String apiSecret;

    @Value("${solis.api.uri}")
    private String solisBaseUri;

    @Value("${solis.api.sn}")
    private String inverterSn;

    // ----- HTTP + JSON -----
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AlertService alerts;

    // ----- Small constants to avoid magic strings -----
    private static final String PATH_INVERTER_DETAIL = "/v1/api/inverterDetail";
    private static final String CONTENT_TYPE_JSON   = "application/json";

    // --- minimal retry/backoff for Solis calls ---
    private static final int[] RETRY_DELAYS_MS = { 500, 1000 }; // 2 retries: 0.5s then 1s

    public SolisCloudClient(AlertService alerts) {
        this.alerts = alerts;
    }


    public Optional<SolisDetail> fetchInverterDetailRich() {
        try {
            // NEW: simple body + retrying POST
            String bodyJson = "{\"sn\":\"" + inverterSn + "\"}";
            Optional<String> bodyOpt = postJsonWithRetry(PATH_INVERTER_DETAIL, bodyJson);
            if (bodyOpt.isEmpty()) {
                alerts.raise("SOLIS_DOWN", "HTTP error or retry exhausted", AlertService.Severity.WARN);
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(bodyOpt.get());


            String apiCode = root.path("code").asText("");
            if (!"0".equals(apiCode)) {
                alerts.raise("SOLIS_DOWN", "API code " + apiCode + " msg=" + root.path("msg").asText(""), AlertService.Severity.WARN);
                return Optional.empty();
            }

            JsonNode d = root.path("data");
            long nowMs = System.currentTimeMillis();

            // Required / existing
            double psumKw = d.path("psum").asDouble(); // + export, - import

            // --- PV power (kW) ---
            // Prefer pac (kW). If pac is missing/near-zero, fall back to dcPac, powTotal/sum(pow1..pow32), then dcAcPower (W).
            Double pacKw = nodeAsNullableDouble(d, "pac");
            Double pvKw  = choosePvKw(d, pacKw);

            // --- Site load (kW) ---
            // Compute physical balance; use API's family/total load only if it agrees within tolerance.
            Double familyApiKw = readWithUnitKw(d, "familyLoadPower", "familyLoadPowerStr");
            Double totalApiKw  = readWithUnitKw(d, "totalLoadPower",  "totalLoadPowerStr");

            double calcLoadKw  = (pvKw != null ? pvKw : 0.0)
                    + (psumKw < 0 ? -psumKw : 0.0)   // import
                    - (psumKw > 0 ?  psumKw : 0.0);  // export

            Double familyLoadKw = pickPlausibleLoad(familyApiKw, totalApiKw, calcLoadKw);



            Integer state = d.path("state").isMissingNode() ? null : d.path("state").asInt();
            Integer warningInfoData = d.path("warningInfoData").isMissingNode() ? null : d.path("warningInfoData").asInt();

            if (log.isDebugEnabled()) {
                log.debug("Solis rich: psum={}kW, pac={}kW, dcPac={}kW, load={}kW, state={}, warn={}",
                        psumKw, pacKw, pvKw, familyLoadKw, state, warningInfoData);
                log.debug("PV choose: pac={} dcPac={} sumPow={} dcAc={} → pv={}",
                        pacKw, readWithUnitKw(d,"dcPac","dcPacStr"), sumPowStringsKw(d),
                        (nodeAsNullableDouble(d,"dcAcPower")), pvKw);
            }

            alerts.resolve("SOLIS_DOWN"); // success
            return Optional.of(new SolisDetail(psumKw, pacKw, pvKw, familyLoadKw, state, warningInfoData, nowMs));
        } catch (Exception ex) {
            alerts.raise("SOLIS_DOWN", "Exception: " + ex.getMessage(), AlertService.Severity.ERROR);
            return Optional.empty();
        }
    }


    /**
     * POST JSON to Solis with HMAC headers. Rebuilds Date/signature each attempt.
     * Returns the body on HTTP 200, otherwise empty after retries.
     */
    private Optional<String> postJsonWithRetry(String path, String bodyJson) {
        for (int attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt++) {
            try {
                // headers depend on Date → rebuild every attempt
                String contentMd5 = md5Base64(bodyJson);
                String dateHeader = httpDateGmt();
                String stringToSign = String.join("\n", "POST", contentMd5, CONTENT_TYPE_JSON, dateHeader, path);
                String signature = signHmacSha1(stringToSign, apiSecret);
                String authHeader = "API " + apiId + ":" + signature;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(solisBaseUri + path))
                        .header("Content-Type", CONTENT_TYPE_JSON)
                        .header("Content-MD5", contentMd5)
                        .header("Date", dateHeader)
                        .header("Authorization", authHeader)
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = resp.statusCode();
                if (sc == 200) return Optional.ofNullable(resp.body());

                if (isRetryableStatus(sc) && attempt < RETRY_DELAYS_MS.length) {
                    log.warn("Solis HTTP {} — retrying in {} ms (attempt {}/{})",
                            sc, RETRY_DELAYS_MS[attempt], attempt + 1, RETRY_DELAYS_MS.length);
                    Thread.sleep(RETRY_DELAYS_MS[attempt]);
                    continue;
                }
                log.warn("Solis HTTP error (no retry): status={} body={}", sc, resp.body());
                return Optional.empty();

            } catch (java.io.IOException e) {
                if (attempt < RETRY_DELAYS_MS.length) {
                    log.warn("Solis I/O error: {} — retrying in {} ms (attempt {}/{})",
                            e.toString(), RETRY_DELAYS_MS[attempt], attempt + 1, RETRY_DELAYS_MS.length);
                    try { Thread.sleep(RETRY_DELAYS_MS[attempt]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                log.warn("Solis I/O error (giving up): {}", e.toString());
                return Optional.empty();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception fatal) {
                log.warn("Solis fatal error (no retry): {}", fatal.toString());
                return Optional.empty();
            }
        }
        return Optional.empty(); // should not reach
    }


    // ===== Helpers (tiny and explicit) =====

    private boolean isRetryableStatus(int sc) {
        return sc == 429 || (sc >= 500 && sc <= 599);
    }

    // Read a value and convert to kW if its paired unit field says "W"
    private static Double readWithUnitKw(JsonNode d, String valueField, String unitField) {
        JsonNode n = d.path(valueField);
        if (!n.isNumber()) return null;
        double v = n.asDouble();
        String unit = d.path(unitField).asText("");
        if ("W".equalsIgnoreCase(unit)) v /= 1000.0;
        return v;
    }

    // PV per-string sum: uses powTotal (W) if present, else sum pow1..pow32 (or Pow1..Pow32), then → kW
    private static Double sumPowStringsKw(JsonNode d) {
        JsonNode tot = d.path("powTotal");
        if (tot.isNumber()) return tot.asDouble() / 1000.0;

        double sumW = 0;
        boolean any = false;
        for (int i = 1; i <= 32; i++) {
            JsonNode a = d.path("pow" + i);
            if (a.isNumber()) { sumW += a.asDouble(); any = true; continue; }
            JsonNode b = d.path("Pow" + i); // some payloads use capital P
            if (b.isNumber()) { sumW += b.asDouble(); any = true; }
        }
        return any ? sumW / 1000.0 : null;
    }

    // Treat "near zero" PV as missing; Solis often reports 0.0 for dcPac while pac/pow* are valid.
    private static final double PV_MIN_VALID_KW = 0.05;   // 50 W

    /** Choose the best PV power (kW). Prefer pac; otherwise dcPac (unit-aware); then powTotal/sum(pow1..32); then dcAcPower (W). */
    private static Double choosePvKw(JsonNode d, Double pacKw) {
        Double dcPacKw    = readWithUnitKw(d, "dcPac", "dcPacStr");
        Double powSumKw   = sumPowStringsKw(d);
        Double dcAcPowerW = nodeAsNullableDouble(d, "dcAcPower");      // often in W, no unit field
        Double dcAcKw     = (dcAcPowerW != null ? dcAcPowerW / 1000.0 : null);

        // prefer pac if it looks real
        if (pacKw != null && pacKw > PV_MIN_VALID_KW) return pacKw;
        if (dcPacKw != null && dcPacKw > PV_MIN_VALID_KW) return dcPacKw;
        if (powSumKw != null && powSumKw > PV_MIN_VALID_KW) return powSumKw;
        if (dcAcKw  != null && dcAcKw  > PV_MIN_VALID_KW) return dcAcKw;

        // fallbacks (could be legitimate zeros)
        if (pacKw   != null) return pacKw;
        if (dcPacKw != null) return dcPacKw;
        if (powSumKw!= null) return powSumKw;
        return dcAcKw;
    }

    /** Pick API load if it’s close to the physical balance; otherwise trust the balance. */
    private static Double pickPlausibleLoad(Double familyApiKw, Double totalApiKw, double computedKw) {
        // tolerance: max(0.6 kW, 35% of computed)
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

    /** RFC-1123 style GMT date that Solis expects in the Date header. */
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

    private static Double nodeAsNullableDouble(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || !v.isNumber()) ? null : v.asDouble();
    }

    public record SolisDetail(
            double psumKw,              // + export, - import (as before)
            Double pacKw,               // inverter AC output power (kW) — may be null
            Double dcPacKw,             // PV total DC power (kW) — converted if API returns W
            Double familyLoadKw,        // site/building load (kW)
            Integer state,              // 1 online, 2 offline, 3 alarm
            Integer warningInfoData,    // alarm info integer
            long fetchedAtMs
    ) {}
}

