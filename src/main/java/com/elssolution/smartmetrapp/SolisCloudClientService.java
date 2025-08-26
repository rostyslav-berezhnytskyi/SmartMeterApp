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

@Slf4j
@Service
public class SolisCloudClientService {

    @Value("${solis.api.id}")
    private String apiId;

    @Value("${solis.api.secret}")
    private String apiSecret;

    @Value("${solis.api.uri}")
    private String solisUri;

    @Value("${solis.api.sn}")
    private String inverterSn;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<Double> getCurrentGridImportPower() {
        try {
            // 1️⃣ Тіло запиту — просто SN
            String bodyJson = String.format("{\"sn\":\"%s\"}", inverterSn);

            // 2️⃣ MD5
            String contentMD5 = calculateContentMD5(bodyJson);

            // 3️⃣ Дата
            String dateHeader = getGMTDate();

            // 4️⃣ Endpoint
            String canonicalPath = "/v1/api/inverterDetail";

            // 5️⃣ Content-Type
            String contentType = "application/json";

            // 6️⃣ Рядок для підпису
            String stringToSign = String.join("\n", "POST", contentMD5, contentType, dateHeader, canonicalPath);

            // 7️⃣ Підпис
            String signature = generateSignature(stringToSign, apiSecret);

            // 8️⃣ Authorization
            String authorization = "API " + apiId + ":" + signature;

            // 9️⃣ Створення запиту
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(solisUri + canonicalPath))
                    .header("Content-Type", contentType)
                    .header("Content-MD5", contentMD5)
                    .header("Date", dateHeader)
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            // 🔟 Надсилання
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("\n=== SolisCloud GridPower Debug (inverterDetail) ===");
            System.out.println("Response Code: " + response.statusCode());
            System.out.println("Response Body:\n" + response.body());
            System.out.println("===================================================");

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if ("0".equals(root.path("code").asText())) {
                    JsonNode data = root.path("data");

                    double psum = data.path("psum").asDouble();

                    if (psum < 0) {
                        System.out.println("Споживає з мережі - " + Math.abs(psum));
                        return Optional.of(Math.abs(psum));
                    } else {
                        System.out.println("Не споживає з мережі");
                        return Optional.of(0.0);
                    }
                } else {
                    log.warn("API Error: {} - {}", root.path("code").asText(), root.path("msg").asText());
                }
            }

        } catch (Exception e) {
            log.error("❌ Exception in getCurrentGridImportPower (detail): {}", e.getMessage(), e);
        }

        return Optional.empty();
    }


    private String calculateContentMD5(String body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private String getGMTDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    private String generateSignature(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
        mac.init(secretKey);
        byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hmacBytes);
    }
}

