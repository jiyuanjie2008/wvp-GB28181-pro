package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.jxt.identity.config.IdentityConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncPayloadSpecific;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DeviceIdentityServiceTest {

    private DeviceIdentityService service;
    private DeviceIdentityMapper identityMapper;
    private DeviceIdentityTxService txService;

    @BeforeEach
    void setUp() {
        service = new DeviceIdentityService();
        identityMapper = mock(DeviceIdentityMapper.class);
        txService = mock(DeviceIdentityTxService.class);
        IdentityConfig identityConfig = new IdentityConfig();
        identityConfig.getIdempotency().setCleanupDays(7);

        ReflectionTestUtils.setField(service, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(service, "txService", txService);
        ReflectionTestUtils.setField(service, "identityConfig", identityConfig);
    }

    private IamSyncRequest validRequest() {
        IamSyncRequest req = new IamSyncRequest();
        req.setSchemaVersion(1);
        req.setOperation("register");
        req.setTargetDeviceId("34020000001320000001");
        req.setIdempotencyKey("test-key-001");
        IamSyncPayloadSpecific payload = new IamSyncPayloadSpecific();
        payload.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        payload.setRealm("3502000000");
        req.setPayloadSpecific(payload);
        return req;
    }

    @Test
    void firstRequest_delegatesToTxService() {
        when(identityMapper.tryInsertIdempotencyLog(anyString(), anyString(), anyString())).thenReturn(1);
        when(txService.doRegister(any())).thenReturn(DeviceIdentityService.DeviceIdentityResult.ok("34020000001320000001", true));

        DeviceIdentityService.DeviceIdentityResult result = service.register(validRequest());

        assertEquals(0, result.code());
        assertTrue(result.created());
        verify(txService).doRegister(any());
    }

    @Test
    void duplicateKey_returnsIdempotentHit() {
        when(identityMapper.tryInsertIdempotencyLog(anyString(), anyString(), anyString()))
                .thenThrow(new DuplicateKeyException("duplicate"));

        DeviceIdentityService.DeviceIdentityResult result = service.register(validRequest());

        assertEquals(0, result.code());
        assertFalse(result.created());
        verify(txService, never()).doRegister(any());
    }

    @Test
    void txServiceFailure_deletesKeyAndRethrows() {
        when(identityMapper.tryInsertIdempotencyLog(anyString(), anyString(), anyString())).thenReturn(1);
        when(txService.doRegister(any())).thenThrow(new RuntimeException("DB error"));
        when(identityMapper.deleteIdempotencyLog(anyString())).thenReturn(1);

        assertThrows(RuntimeException.class, () -> service.register(validRequest()));
        verify(identityMapper).deleteIdempotencyLog("test-key-001");
    }

    @Test
    void cleanup_callsCleanOldEntriesWithConfiguredDays() {
        when(identityMapper.cleanOldEntries(7)).thenReturn(5);
        service.cleanupIdempotencyLog();
        verify(identityMapper).cleanOldEntries(7);
    }
}
