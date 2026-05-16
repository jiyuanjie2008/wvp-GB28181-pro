package com.genersoft.iot.vmp.jxt.identity.controller;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncPayloadSpecific;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.service.DeviceIdentityService;
import com.genersoft.iot.vmp.vmanager.bean.WVPResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceIdentityControllerTest {

    private DeviceIdentityController controller;
    private DeviceIdentityService identityService;

    @BeforeEach
    void setUp() {
        controller = new DeviceIdentityController();
        identityService = mock(DeviceIdentityService.class);
        when(identityService.register(any())).thenAnswer(invocation -> {
            IamSyncRequest req = invocation.getArgument(0);
            return DeviceIdentityService.DeviceIdentityResult.ok(req.getTargetDeviceId(), true);
        });
        ReflectionTestUtils.setField(controller, "identityService", identityService);
        SipConfig sipConfig = new SipConfig();
        sipConfig.setDomain("3502000000");
        ReflectionTestUtils.setField(controller, "sipConfig", sipConfig);
    }

    private IamSyncRequest validRequest() {
        IamSyncRequest req = new IamSyncRequest();
        req.setSchemaVersion(1);
        req.setOperation("register");
        req.setTargetDeviceId("34020000001320000001");
        req.setIdempotencyKey("test-key-001");
        IamSyncPayloadSpecific payload = new IamSyncPayloadSpecific();
        payload.setDeviceName("TestCamera");
        payload.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        payload.setRealm("3502000000");
        req.setPayloadSpecific(payload);
        return req;
    }

    @Test
    void validRegister_returnsSuccess() {
        WVPResult<DeviceIdentityController.DeviceIdentityData> result = controller.register(validRequest());
        assertEquals(0, result.getCode());
        assertEquals("34020000001320000001", result.getData().deviceId());
        assertTrue(result.getData().created());
    }

    @Test
    void invalidSchemaVersion_returns13001() {
        IamSyncRequest req = validRequest();
        req.setSchemaVersion(2);
        assertEquals(13001, controller.register(req).getCode());
    }

    @Test
    void invalidOperation_returns13002() {
        IamSyncRequest req = validRequest();
        req.setOperation("update");
        assertEquals(13002, controller.register(req).getCode());
    }

    @Test
    void invalidDeviceId_returns13003() {
        IamSyncRequest req = validRequest();
        req.setTargetDeviceId("abc");
        assertEquals(13003, controller.register(req).getCode());
    }

    @Test
    void missingIdempotencyKey_returns13006() {
        IamSyncRequest req = validRequest();
        req.setIdempotencyKey("");
        assertEquals(13006, controller.register(req).getCode());
    }

    @Test
    void nullPayloadSpecific_returns13009() {
        IamSyncRequest req = validRequest();
        req.setPayloadSpecific(null);
        assertEquals(13009, controller.register(req).getCode());
    }

    @Test
    void missingSipHa1_returns13007() {
        IamSyncRequest req = validRequest();
        req.getPayloadSpecific().setSipHa1("");
        assertEquals(13007, controller.register(req).getCode());
    }

    @Test
    void invalidSipHa1Format_returns13004() {
        IamSyncRequest req = validRequest();
        req.getPayloadSpecific().setSipHa1("not-hex");
        assertEquals(13004, controller.register(req).getCode());
    }

    @Test
    void missingRealm_returns13008() {
        IamSyncRequest req = validRequest();
        req.getPayloadSpecific().setRealm("");
        assertEquals(13008, controller.register(req).getCode());
    }

    @Test
    void realmMismatch_returns13005() {
        IamSyncRequest req = validRequest();
        req.getPayloadSpecific().setRealm("9999999999");
        assertEquals(13005, controller.register(req).getCode());
    }
}
