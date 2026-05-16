package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamSyncRequest {
    @JsonProperty("schema_version")
    private int schemaVersion;

    @JsonProperty("idempotency_key")
    private String idempotencyKey;

    @JsonProperty("trace_id")
    private String traceId;

    @JsonProperty("tenant_id")
    private int tenantId;

    @JsonProperty("target_deviceId")
    private String targetDeviceId;

    private String operation;

    @JsonProperty("occurred_at")
    private String occurredAt;

    @JsonProperty("payload_specific")
    private IamSyncPayloadSpecific payloadSpecific;
}
