package com.elssolution.smartmetrapp;

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
public class SolisCloudClientService {

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

    public SolisCloudClientService(AlertService alerts) {
        this.alerts = alerts;
    }


    public Optional<SolisDetail> fetchInverterDetailRich() {
        try {
            String bodyJson = "{\"sn\":\"" + inverterSn + "\"}";

            String contentMd5 = md5Base64(bodyJson);
            String dateHeader = httpDateGmt();
            String stringToSign = String.join("\n", "POST", contentMd5, CONTENT_TYPE_JSON, dateHeader, PATH_INVERTER_DETAIL);
            String signature = signHmacSha1(stringToSign, apiSecret);
            String authHeader = "API " + apiId + ":" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(solisBaseUri + PATH_INVERTER_DETAIL))
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .header("Content-MD5", contentMd5)
                    .header("Date", dateHeader)
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("SolisCloud HTTP error: status={} body={}", response.statusCode(), response.body());
                alerts.raise("SOLIS_DOWN", "HTTP " + response.statusCode(), AlertService.Severity.WARN);
                return Optional.empty();
            }

            JsonNode root  = objectMapper.readTree(response.body());
            String apiCode = root.path("code").asText("");
            if (!"0".equals(apiCode)) {
                alerts.raise("SOLIS_DOWN", "API code " + apiCode + " msg=" + root.path("msg").asText(""), AlertService.Severity.WARN);
                if (log.isDebugEnabled()) log.debug("SolisCloud full response: {}", response.body());
                return Optional.empty();
            }

            JsonNode d = root.path("data");
            long nowMs = System.currentTimeMillis();

            // Required / existing
            double psumKw = d.path("psum").asDouble(); // + export, - import

            // Optional fields (null-safe)
            Double pacKw = nodeAsNullableDouble(d, "pac");           // kW per spec
            // dcPac is documented with dcPacStr unit; convert to kW if needed
            Double dcPacRaw = nodeAsNullableDouble(d, "dcPac");
            String dcPacUnit = d.path("dcPacStr").asText("");        // "W" or "kW"
            Double dcPacKw = (dcPacRaw == null) ? null :
                    ("W".equalsIgnoreCase(dcPacUnit) ? dcPacRaw / 1000.0 : dcPacRaw);

            Double familyLoadKw = nodeAsNullableDouble(d, "familyLoadPower"); // kW

            Integer state = d.path("state").isMissingNode() ? null : d.path("state").asInt();
            Integer warningInfoData = d.path("warningInfoData").isMissingNode() ? null : d.path("warningInfoData").asInt();

            if (log.isDebugEnabled()) {
                log.debug("Solis rich: psum={}kW, pac={}kW, dcPac={}kW, load={}kW, state={}, warn={}",
                        psumKw, pacKw, dcPacKw, familyLoadKw, state, warningInfoData);
            }

            alerts.resolve("SOLIS_DOWN"); // success
            return Optional.of(new SolisDetail(psumKw, pacKw, dcPacKw, familyLoadKw, state, warningInfoData, nowMs));
        } catch (Exception ex) {
            alerts.raise("SOLIS_DOWN", "Exception: " + ex.getMessage(), AlertService.Severity.ERROR);
            return Optional.empty();
        }
    }

    // ===== Helpers (tiny and explicit) =====

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

    /**
     * Simple DTO for the raw Solis datapoint we care about.
     */
    public record SolisReading(double psumKw, long fetchedAtMs) {}

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

