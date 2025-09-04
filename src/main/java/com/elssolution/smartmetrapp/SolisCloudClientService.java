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

    // ----- Small constants to avoid magic strings -----
    private static final String PATH_INVERTER_DETAIL = "/v1/api/inverterDetail";
    private static final String CONTENT_TYPE_JSON   = "application/json";

    /**
     * Calls SolisCloud /v1/api/inverterDetail and returns the raw reading.
     *
     * @return Optional with {@link SolisReading} when successful; empty if any HTTP/API/JSON error occurs.
     *         psumKw sign convention (from Solis): + means export to grid, - means import from grid.
     */
    public Optional<SolisReading> fetchInverterDetail() {
        try {
            // 1) Body: just the inverter SN
            String bodyJson = "{\"sn\":\"" + inverterSn + "\"}";

            // 2) Required headers
            String contentMd5 = md5Base64(bodyJson);
            String dateHeader = httpDateGmt();
            String stringToSign = String.join("\n", "POST", contentMd5, CONTENT_TYPE_JSON, dateHeader, PATH_INVERTER_DETAIL);
            String signature = signHmacSha1(stringToSign, apiSecret);
            String authHeader = "API " + apiId + ":" + signature;

            // 3) Build request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(solisBaseUri + PATH_INVERTER_DETAIL))
                    .header("Content-Type", CONTENT_TYPE_JSON)
                    .header("Content-MD5", contentMd5)
                    .header("Date", dateHeader)
                    .header("Authorization", authHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            // 4) Send
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("SolisCloud HTTP error: status={} body={}", response.statusCode(), response.body());
                return Optional.empty();
            }

            // 5) Parse JSON
            JsonNode root = objectMapper.readTree(response.body());
            String apiCode = root.path("code").asText("");
            if (!"0".equals(apiCode)) {
                log.warn("SolisCloud API error: code={} msg={}", apiCode, root.path("msg").asText(""));
                if (log.isDebugEnabled()) {
                    log.debug("SolisCloud full response (non-zero code): {}", response.body());
                }
                return Optional.empty();
            }

            JsonNode data = root.path("data");
            double psumKw = data.path("psum").asDouble(); // + export, - import (Solis convention)
            long fetchedAtMs = System.currentTimeMillis();

            if (log.isDebugEnabled()) {
                log.debug("SolisCloud raw reading: psumKw={} ( + export / - import ), fetchedAtMs={}",
                        psumKw, fetchedAtMs);
            }

            return Optional.of(new SolisReading(psumKw, fetchedAtMs));

        } catch (Exception ex) {
            log.warn("SolisCloud request failed: {}", ex.getMessage(), ex);
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

    /**
     * Simple DTO for the raw Solis datapoint we care about.
     * Keep it here for locality, or move to its own file if you prefer.
     */
    public record SolisReading(double psumKw, long fetchedAtMs) {}
}

