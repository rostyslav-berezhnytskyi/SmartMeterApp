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

    @Value("${solis.api.sn}")
    private String inverterSn;

    @Value("${solis.api.timezone}")
    private int timeZone;

    private static final String API_URL = "https://www.soliscloud.com:13333/v1/api/inverterDay";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();


    public void testApiConnection() {
        try {
            // 1️⃣ Формуємо тіло запиту у правильному форматі (без пробілів)
            String bodyJson = "{\"pageNo\":1,\"pageSize\":10}"; // Вручну, щоб уникнути пробілів

            // 2️⃣ MD5 тіла (в Base64)
            String contentMD5 = calculateContentMD5(bodyJson);

            // 3️⃣ Дата (без лапок навколо 'GMT')
            String dateHeader = getGMTDate();

            // 4️⃣ Canonical Path — строго з документа
            String canonicalPath = "/v1/api/inverterList";

            // 5️⃣ Content-Type — важливо: саме "application/json", без charset!
            String contentType = "application/json";

            // 6️⃣ Формуємо рядок для підпису (як на скріні)
            String stringToSign = String.join("\n", "POST", contentMD5, contentType, dateHeader, canonicalPath);

            // 7️⃣ Генеруємо підпис
            String signature = generateSignature(stringToSign, apiSecret);

            // 8️⃣ Authorization заголовок
            String authorization = "API " + apiId + ":" + signature;

            // 9️⃣ Створюємо HTTP-запит
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.soliscloud.com:13333" + canonicalPath))
                    .header("Content-Type", contentType)
                    .header("Content-MD5", contentMD5)
                    .header("Date", dateHeader)
                    .header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            // 🔟 Відправляємо запит
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 🔍 Виводимо всі поля для дебагу
            System.out.println("\n=== Test inverterList Debug ===");
            System.out.println("Body JSON:\n" + bodyJson);
            System.out.println("Content-MD5: " + contentMD5);
            System.out.println("Date: " + dateHeader);
            System.out.println("String to Sign:\n" + stringToSign);
            System.out.println("Signature: " + signature);
            System.out.println("Authorization: " + authorization);
            System.out.println("Response Code: " + response.statusCode());
            System.out.println("Response Body:\n" + response.body());
            System.out.println("===============================");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Optional<Double> getCurrentGridPower() {
        try {
            // 📅 Формат дати для запиту
            String currentDate = new SimpleDateFormat("yyyy-MM-dd").format(new Date());

            // 🧱 Формуємо тіло запиту (JSON)
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("sn", inverterSn);
            requestBody.put("time", currentDate);
            requestBody.put("timeZone", timeZone);
            String bodyJson = objectMapper.writeValueAsString(requestBody);

            // 🧮 Обчислюємо Content-MD5
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md5.digest(bodyJson.getBytes(StandardCharsets.UTF_8));
            String contentMD5 = Base64.getEncoder().encodeToString(md5Bytes);

            // 🕒 Поточна дата у GMT для заголовка Date
            String dateHeader = getGMTDate();

            // 📜 Canonical Path — строго з документа
            String canonicalPath = "/v1/api/inverterDay";

            // 🔏 Формуємо рядок для підпису
            String stringToSign = String.join("\n",
                    "POST",
                    contentMD5,
                    "application/json;charset=UTF-8",
                    dateHeader,
                    canonicalPath
            );

            // 🔐 Генеруємо підпис (signature)
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String signature = Base64.getEncoder().encodeToString(rawHmac);

            // 🔐 Authorization заголовок
            String authorizationHeader = "API " + apiId + ":" + signature;

            // 📤 Формуємо HTTP-запит
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("Content-MD5", contentMD5)
                    .header("Date", dateHeader)
                    .header("Authorization", authorizationHeader)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            // 📬 Відправляємо запит
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 🧾 Debug log
            System.out.println("\n=== SolisCloud Request Debug ===");
            System.out.println("Body JSON:\n" + bodyJson);
            System.out.println("Content-MD5: " + contentMD5);
            System.out.println("Date: " + dateHeader);
            System.out.println("String to Sign:\n" + stringToSign);
            System.out.println("Signature: " + signature);
            System.out.println("Authorization: " + authorizationHeader);
            System.out.println("Response Code: " + response.statusCode());
            System.out.println("Response Body:\n" + response.body());
            System.out.println("================================");

            // 📦 Обробляємо відповідь
            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                if ("0".equals(root.path("code").asText())) {
                    JsonNode dataArray = root.path("data");
                    if (dataArray.isArray() && !dataArray.isEmpty()) {
                        JsonNode latest = dataArray.get(dataArray.size() - 1);
                        return Optional.of(latest.path("pSum").asDouble());
                    }
                } else {
                    log.warn("❗ API returned error code {}: {}", root.path("code").asText(), root.path("msg").asText());
                }
            } else {
                log.error("❗ API HTTP error {}: {}", response.statusCode(), response.body());
            }

        } catch (Exception e) {
            log.error("❌ Exception while calling SolisCloud API: {}", e.getMessage(), e);
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

