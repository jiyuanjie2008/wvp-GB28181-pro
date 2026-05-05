package com.genersoft.iot.vmp.jxt.callback;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CallbackEvent {
    private String eventId;
    private String eventType;
    private String deviceId;
    private String payloadJson;
    private LocalDateTime sentAt;
    private LocalDateTime ackedAt;
    private int ackAttempts;
    private Integer lastHttpCode;
    private String status;
}
