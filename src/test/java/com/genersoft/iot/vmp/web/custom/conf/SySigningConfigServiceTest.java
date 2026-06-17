package com.genersoft.iot.vmp.web.custom.conf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SySigningConfigServiceTest {

    private SySigningConfigService service;

    @BeforeEach
    void setUp() {
        SyTokenManager.INSTANCE.current = null;
        service = new SySigningConfigService();
    }

    @Test
    void testApplyConfigValue_validJson_updatesSyTokenManager() {
        String json = "{\"appKey\":\"test-key-1234\",\"appSecret\":\"test-secret-64chars...\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertTrue(result, "valid config should be applied");
        SySigningSnapshot snap = SyTokenManager.INSTANCE.current;
        assertNotNull(snap);
        assertEquals("test-secret-64chars...", snap.appMap().get("test-key-1234"));
        assertEquals("32hexsm4key12345678abcdef", snap.sm4Key());
        assertEquals(30L, snap.expires());
    }

    @Test
    void testApplyConfigValue_adminTokenGeneratedOnlyOnce() {
        String json1 = "{\"appKey\":\"key1\",\"appSecret\":\"secret1\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";
        String json2 = "{\"appKey\":\"key2\",\"appSecret\":\"secret2\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json1);
        String adminTokenAfterFirst = SyTokenManager.INSTANCE.current.adminToken();
        assertNotNull(adminTokenAfterFirst, "adminToken should be generated on first apply");
        assertNotEquals("", adminTokenAfterFirst);

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json2);
        assertEquals(adminTokenAfterFirst, SyTokenManager.INSTANCE.current.adminToken(),
                "adminToken should NOT change on rotation");
    }

    @Test
    void testApplyConfigValue_missingFields_returnsFalse() {
        // Missing appSecret
        String json = "{\"appKey\":\"test-key\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertFalse(result, "incomplete config should not be applied");
        assertNull(SyTokenManager.INSTANCE.current, "snapshot should remain null on failed apply");
    }

    @Test
    void testApplyConfigValue_emptyFields_returnsFalse() {
        String json = "{\"appKey\":\"\",\"appSecret\":\"\",\"sm4Key\":\"\",\"expiresMin\":30}";

        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertFalse(result, "empty fields should not be applied");
        assertNull(SyTokenManager.INSTANCE.current, "snapshot should remain null on failed apply");
    }

    @Test
    void testApplyConfigValue_invalidJson_returnsFalse() {
        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", "not-json");

        assertFalse(result, "invalid JSON should not be applied");
        assertNull(SyTokenManager.INSTANCE.current, "snapshot should remain null on failed apply");
    }

    @Test
    void testApplyConfigValue_rotationReplacesAppKey() {
        String json1 = "{\"appKey\":\"key-original\",\"appSecret\":\"secret1\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";
        String json2 = "{\"appKey\":\"key-rotated\",\"appSecret\":\"secret2\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json1);
        SySigningSnapshot snap1 = SyTokenManager.INSTANCE.current;
        assertNotNull(snap1.appMap().get("key-original"));
        assertNull(snap1.appMap().get("key-rotated"));

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json2);
        SySigningSnapshot snap2 = SyTokenManager.INSTANCE.current;
        assertNull(snap2.appMap().get("key-original"), "old key should be removed on rotation");
        assertNotNull(snap2.appMap().get("key-rotated"), "new key should be present");

        // snap1 应不受 snap2 影响（不可变快照）
        assertNotNull(snap1.appMap().get("key-original"), "old snapshot should be preserved");
    }

    @Test
    void testIsLoaded_falseBeforeSuccessfulApply() {
        assertFalse(service.isLoaded());
    }

    @Test
    void testIsLoaded_trueAfterSuccessfulApply() {
        String json = "{\"appKey\":\"test-key\",\"appSecret\":\"test-secret\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertTrue(service.isLoaded());
    }

    @Test
    void testApplyConfigValue_usesDefaultExpiresMin_whenNull() {
        String json = "{\"appKey\":\"test-key\",\"appSecret\":\"test-secret\",\"sm4Key\":\"32hexsm4key12345678abcdef\"}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertEquals(30L, SyTokenManager.INSTANCE.current.expires(), "default expiresMin should be 30 when not provided");
    }

    @Test
    void testApplyConfigValue_negativeExpiresMin_clampedToDefault() {
        // 与 security-management Go 端 NewSignClient 对齐: 非正 expiresMin 回退为 30,
        // 否则 SignAuthenticationFilter 会把所有请求判为过期(code 3)。
        String json = "{\"appKey\":\"test-key\",\"appSecret\":\"test-secret\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":-5}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertEquals(30L, SyTokenManager.INSTANCE.current.expires(), "negative expiresMin should be clamped to 30");
    }

    @Test
    void testApplyConfigValue_zeroExpiresMin_clampedToDefault() {
        String json = "{\"appKey\":\"test-key\",\"appSecret\":\"test-secret\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":0}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertEquals(30L, SyTokenManager.INSTANCE.current.expires(), "zero expiresMin should be clamped to 30");
    }
}
