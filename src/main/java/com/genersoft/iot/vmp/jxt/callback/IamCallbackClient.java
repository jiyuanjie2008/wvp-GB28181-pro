package com.genersoft.iot.vmp.jxt.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class IamCallbackClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    @Autowired
    private IamCallbackConfig config;

    @Autowired
    private CallbackEventMapper callbackEventMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void sendCallback(String eventType, String deviceId, Map<String, Object> payload) {
        if (!config.isEnabled()) {
            return;
        }

        String eventId = UUID.randomUUID().toString().replace("-", "").substring(0, 26);
        CallbackEvent event = new CallbackEvent();
        event.setEventId(eventId);
        event.setEventType(eventType);
        event.setDeviceId(deviceId);
        event.setSentAt(LocalDateTime.now());
        event.setAckAttempts(0);
        event.setStatus("pending");

        try {
            Map<String, Object> body = new HashMap<>();
            body.put("eventId", eventId);
            body.put("eventType", eventType);
            body.put("deviceId", deviceId);
            body.put("timestamp", System.currentTimeMillis() / 1000);
            body.put("payload", payload);
            String bodyJson = objectMapper.writeValueAsString(body);
            event.setPayloadJson(bodyJson);

            callbackEventMapper.insert(event);

            doSend(event, bodyJson);
        } catch (Exception e) {
            log.error("[IAM回调] 构造事件失败: eventType={}, deviceId={}", eventType, deviceId, e);
        }
    }

    private void doSend(CallbackEvent event, String bodyJson) {
        String url = config.getIamBaseUrl().replaceAll("/+$", "")
                + "/api/v1/wvp-callback/device/" + event.getEventType();

        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString();

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(bodyJson, JSON_TYPE))
                .header("X-WVP-Callback-Key", config.getActiveKey())
                .header("X-WVP-Callback-Timestamp", timestamp)
                .header("X-WVP-Callback-Nonce", nonce)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int httpCode = response.code();
            if (httpCode >= 200 && httpCode < 300) {
                callbackEventMapper.updateStatus(event.getEventId(), "acked",
                        LocalDateTime.now(), event.getAckAttempts(), httpCode);
                log.debug("[IAM回调] 成功: eventType={}, deviceId={}", event.getEventType(), event.getDeviceId());
            } else {
                int attempts = event.getAckAttempts() + 1;
                String status = attempts >= 5 ? "dead_letter" : "pending";
                callbackEventMapper.updateStatus(event.getEventId(), status,
                        null, attempts, httpCode);
                log.warn("[IAM回调] 失败: eventType={}, deviceId={}, httpCode={}, attempts={}",
                        event.getEventType(), event.getDeviceId(), httpCode, attempts);
            }
        } catch (IOException e) {
            int attempts = event.getAckAttempts() + 1;
            String status = attempts >= 5 ? "dead_letter" : "pending";
            callbackEventMapper.updateStatus(event.getEventId(), status,
                    null, attempts, null);
            log.warn("[IAM回调] 网络错误: eventType={}, deviceId={}, attempts={}",
                    event.getEventType(), event.getDeviceId(), attempts, e);
        }
    }
}
