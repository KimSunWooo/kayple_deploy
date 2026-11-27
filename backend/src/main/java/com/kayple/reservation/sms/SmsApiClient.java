package com.kayple.reservation.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class SmsApiClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String apiKey;

    public SmsApiClient(
            ObjectMapper objectMapper,
            @Value("${app.sms.api-url}") String apiUrl,
            @Value("${app.sms.api-key:}") String apiKey,
            @Value("${app.sms.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${app.sms.read-timeout-ms:15000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.readTimeoutMs = readTimeoutMs;
    }

    private final int readTimeoutMs;

    public SmsSendResult send(String to, String text, String idempotencyKey) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("to", to);
            body.put("text", text);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String errorCode = null;
            String errorMessage = null;
            String messageId = null;
            Optional<Integer> retryAfter = Optional.empty();

            if (response.body() != null && !response.body().isBlank()) {
                JsonNode node = objectMapper.readTree(response.body());
                if (node.hasNonNull("messageId")) {
                    messageId = node.get("messageId").asText();
                }
                if (node.hasNonNull("error")) {
                    errorCode = node.get("error").asText();
                }
                if (node.hasNonNull("message")) {
                    errorMessage = node.get("message").asText();
                }
            }

            String retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null);
            if (retryAfterHeader != null) {
                try {
                    retryAfter = Optional.of(Integer.parseInt(retryAfterHeader.trim()));
                } catch (NumberFormatException ignored) {
                    retryAfter = Optional.empty();
                }
            }

            return new SmsSendResult(response.statusCode(), messageId, errorCode, errorMessage, retryAfter.orElse(null), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SmsSendResult.networkError("Interrupted: " + e.getMessage());
        } catch (Exception e) {
            return SmsSendResult.networkError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public record SmsSendResult(
            int statusCode,
            String messageId,
            String errorCode,
            String errorMessage,
            Integer retryAfterSeconds,
            boolean networkError
    ) {
        public static SmsSendResult networkError(String message) {
            return new SmsSendResult(-1, null, "NETWORK_ERROR", message, null, true);
        }
    }
}
