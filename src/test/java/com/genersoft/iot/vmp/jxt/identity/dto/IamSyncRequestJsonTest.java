package com.genersoft.iot.vmp.jxt.identity.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IamSyncRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializeFullPayload() throws Exception {
        String json = """
        {
          "schema_version": 1,
          "idempotency_key": "iam-reg-123-abc456",
          "trace_id": "00-abc456def-01",
          "tenant_id": 1,
          "target_deviceId": "34020000001320000001",
          "operation": "register",
          "occurred_at": "2026-05-16T10:00:00Z",
          "payload_specific": {
            "deviceName": "TestCamera",
            "sipHa1": "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
            "realm": "3502000000",
            "charset": "GB2312",
            "streamMode": "TCP-PASSIVE",
            "sdpIp": "192.168.1.100",
            "mediaServerId": "auto",
            "ssrcCheck": false,
            "geoCoordSys": "WGS84",
            "asMessageChannel": false,
            "broadcastPushAfterAck": false,
            "heartbeatInterval": 60,
            "heartbeatCount": 3
          }
        }
        """;

        IamSyncRequest request = mapper.readValue(json, IamSyncRequest.class);

        assertEquals(1, request.getSchemaVersion());
        assertEquals("iam-reg-123-abc456", request.getIdempotencyKey());
        assertEquals("00-abc456def-01", request.getTraceId());
        assertEquals(1, request.getTenantId());
        assertEquals("34020000001320000001", request.getTargetDeviceId());
        assertEquals("register", request.getOperation());
        assertEquals("2026-05-16T10:00:00Z", request.getOccurredAt());
        assertNotNull(request.getPayloadSpecific());
        assertEquals("TestCamera", request.getPayloadSpecific().getDeviceName());
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", request.getPayloadSpecific().getSipHa1());
        assertEquals("3502000000", request.getPayloadSpecific().getRealm());
        assertEquals("GB2312", request.getPayloadSpecific().getCharset());
        assertEquals("TCP-PASSIVE", request.getPayloadSpecific().getStreamMode());
        assertEquals("192.168.1.100", request.getPayloadSpecific().getSdpIp());
        assertEquals("auto", request.getPayloadSpecific().getMediaServerId());
        assertFalse(request.getPayloadSpecific().getSsrcCheck());
        assertEquals("WGS84", request.getPayloadSpecific().getGeoCoordSys());
        assertFalse(request.getPayloadSpecific().getAsMessageChannel());
        assertFalse(request.getPayloadSpecific().getBroadcastPushAfterAck());
        assertEquals(60, request.getPayloadSpecific().getHeartbeatInterval());
        assertEquals(3, request.getPayloadSpecific().getHeartbeatCount());
    }

    @Test
    void deserializeMinimalPayload() throws Exception {
        String json = """
        {
          "schema_version": 1,
          "idempotency_key": "key-001",
          "trace_id": "trace-001",
          "tenant_id": 2,
          "target_deviceId": "34020000001320000002",
          "operation": "register",
          "occurred_at": "2026-05-16T12:00:00Z",
          "payload_specific": {
            "deviceName": "Cam002",
            "sipHa1": "ffffffffffffffffffffffffffffffff",
            "realm": "3502000000"
          }
        }
        """;

        IamSyncRequest request = mapper.readValue(json, IamSyncRequest.class);

        assertNull(request.getPayloadSpecific().getCharset());
        assertNull(request.getPayloadSpecific().getStreamMode());
        assertNull(request.getPayloadSpecific().getHeartbeatInterval());
        assertNull(request.getPayloadSpecific().getHeartbeatCount());
        assertNull(request.getPayloadSpecific().getSdpIp());
        assertNull(request.getPayloadSpecific().getMediaServerId());
        assertNull(request.getPayloadSpecific().getSsrcCheck());
        assertNull(request.getPayloadSpecific().getGeoCoordSys());
        assertNull(request.getPayloadSpecific().getAsMessageChannel());
        assertNull(request.getPayloadSpecific().getBroadcastPushAfterAck());
    }

    @Test
    void deserializeWithUnknownFieldsSilentlyIgnored() throws Exception {
        String json = """
        {
          "schema_version": 1,
          "idempotency_key": "key-002",
          "trace_id": "trace-002",
          "tenant_id": 1,
          "target_deviceId": "34020000001320000003",
          "operation": "register",
          "occurred_at": "2026-05-16T14:00:00Z",
          "unknown_top_level": "ignored",
          "payload_specific": {
            "deviceName": "Cam003",
            "sipHa1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "realm": "3502000000",
            "futureField": "also ignored"
          }
        }
        """;

        IamSyncRequest request = mapper.readValue(json, IamSyncRequest.class);

        assertEquals("Cam003", request.getPayloadSpecific().getDeviceName());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", request.getPayloadSpecific().getSipHa1());
    }
}
