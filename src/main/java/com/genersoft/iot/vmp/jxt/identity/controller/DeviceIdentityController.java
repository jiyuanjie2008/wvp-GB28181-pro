package com.genersoft.iot.vmp.jxt.identity.controller;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.service.DeviceIdentityService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping(value = "/api/sy")
@ConditionalOnExpression("'${sy.enable:false}' == 'true' && '${jxt.identity.controller.enabled:true}' == 'true'")
public class DeviceIdentityController {

    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^\\d{20}$");
    private static final Pattern HA1_PATTERN = Pattern.compile("^[0-9a-fA-F]{32}$");

    @Autowired
    private DeviceIdentityService identityService;

    @Autowired
    private SipConfig sipConfig;

    @PostMapping(value = "/device", consumes = "application/json", produces = "application/json")
    public WVPResult<DeviceIdentityData> register(@RequestBody(required = false) IamSyncRequest request) {
        if (request == null) {
            return fail(13000, "Missing request body");
        }
        log.info("IAM sync: operation={}, device={}, key={}", request.getOperation(),
                request.getTargetDeviceId(), request.getIdempotencyKey());

        // --- Input validation ---
        if (request.getSchemaVersion() != 1) {
            return fail(13001, "Unsupported schema_version: expected 1, got " + request.getSchemaVersion());
        }
        if (!"register".equals(request.getOperation())) {
            return fail(13002, "Unsupported operation: expected 'register', got '" + request.getOperation() + "'");
        }
        if (request.getTargetDeviceId() == null || !DEVICE_ID_PATTERN.matcher(request.getTargetDeviceId()).matches()) {
            return fail(13003, "Invalid target_deviceId: expected 20-digit number");
        }
        if (ObjectUtils.isEmpty(request.getIdempotencyKey())) {
            return fail(13006, "Missing idempotency_key");
        }
        if (request.getPayloadSpecific() == null) {
            return fail(13009, "Missing payload_specific");
        }
        if (ObjectUtils.isEmpty(request.getPayloadSpecific().getSipHa1())) {
            return fail(13007, "Missing payload_specific.sipHa1");
        }
        if (!HA1_PATTERN.matcher(request.getPayloadSpecific().getSipHa1()).matches()) {
            return fail(13004, "Invalid sipHa1 format: expected 32 hex chars (MD5 digest)");
        }
        if (ObjectUtils.isEmpty(request.getPayloadSpecific().getRealm())) {
            return fail(13008, "Missing payload_specific.realm");
        }
        if (!request.getPayloadSpecific().getRealm().equals(sipConfig.getDomain())) {
            return fail(13005, "Realm mismatch: expected '" + sipConfig.getDomain() +
                    "', got '" + request.getPayloadSpecific().getRealm() + "'");
        }

        // --- Process ---
        DeviceIdentityService.DeviceIdentityResult result = identityService.register(request);
        if (result.code() != 0) {
            return fail(result.code(), result.msg());
        }

        return WVPResult.success(new DeviceIdentityData(result.deviceId(), result.created()));
    }

    private WVPResult<DeviceIdentityData> fail(int code, String msg) {
        log.warn("IAM sync rejected: code={}, msg={}", code, msg);
        return WVPResult.fail(code, msg);
    }

    public record DeviceIdentityData(String deviceId, Boolean created) {}

    @PostMapping(value = "/device/batch-register", consumes = "application/json", produces = "application/json")
    public WVPResult<BatchRegisterResult> batchRegister(@RequestBody(required = false) BatchRegisterRequest request) {
        if (request == null) {
            return WVPResult.fail(13010, "Missing request body");
        }
        if (request.getSchemaVersion() != 1) {
            return WVPResult.fail(13011, "Unsupported schema_version: expected 1, got " + request.getSchemaVersion());
        }
        if (request.getEntries() == null || request.getEntries().isEmpty()) {
            return WVPResult.fail(13012, "Missing or empty entries");
        }

        int created = 0;
        int updated = 0;
        int failed = 0;
        for (IamSyncRequest entry : request.getEntries()) {
            try {
                DeviceIdentityService.DeviceIdentityResult result = identityService.register(entry);
                if (result.code() != 0) {
                    failed++;
                } else if (result.created()) {
                    created++;
                } else {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("Batch register: failed for device={}, error={}", entry.getTargetDeviceId(), e.getMessage());
                failed++;
            }
        }

        log.info("Batch register: created={}, updated={}, failed={}", created, updated, failed);
        return WVPResult.success(new BatchRegisterResult(created, updated, failed));
    }

    @Data
    public static class BatchRegisterRequest {
        @JsonProperty("schema_version")
        private int schemaVersion;
        private List<IamSyncRequest> entries;
    }

    public record BatchRegisterResult(int created, int updated, int failed) {}
}
