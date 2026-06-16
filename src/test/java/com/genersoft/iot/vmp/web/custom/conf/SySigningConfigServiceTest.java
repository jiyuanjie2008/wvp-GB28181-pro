package com.genersoft.iot.vmp.web.custom.conf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class SySigningConfigServiceTest {

    private SySigningConfigService service;

    @BeforeEach
    void setUp() {
        service = new SySigningConfigService();
    }

    @Test
    void testApplyConfigValue_validJson_updatesSyTokenManager() {
        String json = "{\"appKey\":\"test-key-1234\",\"appSecret\":\"test-secret-64chars...\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertTrue(result, "valid config should be applied");
        assertEquals("test-key-1234", SyTokenManager.INSTANCE.appMap.get("test-key-1234"));
        assertEquals("32hexsm4key12345678abcdef", SyTokenManager.INSTANCE.sm4Key);
        assertEquals(30L, SyTokenManager.INSTANCE.expires);
    }

    @Test
    void testApplyConfigValue_adminTokenGeneratedOnlyOnce() {
        String json1 = "{\"appKey\":\"key1\",\"appSecret\":\"secret1\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";
        String json2 = "{\"appKey\":\"key2\",\"appSecret\":\"secret2\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        String adminTokenBefore = SyTokenManager.INSTANCE.adminToken;

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json1);
        String tokenAfterFirst = SyTokenManager.INSTANCE.adminToken;
        assertNotNull(tokenAfterFirst, "adminToken should be generated on first apply");
        assertNotEquals("", tokenAfterFirst);

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json2);
        assertEquals(tokenAfterFirst, SyTokenManager.INSTANCE.adminToken,
                "adminToken should NOT change on rotation");
    }

    @Test
    void testApplyConfigValue_missingFields_returnsFalse() {
        // Missing appSecret
        String json = "{\"appKey\":\"test-key\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertFalse(result, "incomplete config should not be applied");
    }

    @Test
    void testApplyConfigValue_emptyFields_returnsFalse() {
        String json = "{\"appKey\":\"\",\"appSecret\":\"\",\"sm4Key\":\"\",\"expiresMin\":30}";

        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json);

        assertFalse(result, "empty fields should not be applied");
    }

    @Test
    void testApplyConfigValue_invalidJson_returnsFalse() {
        boolean result = ReflectionTestUtils.invokeMethod(service, "applyConfigValue", "not-json");

        assertFalse(result, "invalid JSON should not be applied");
    }

    @Test
    void testApplyConfigValue_rotationReplacesAppKey() {
        String json1 = "{\"appKey\":\"key-original\",\"appSecret\":\"secret1\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";
        String json2 = "{\"appKey\":\"key-rotated\",\"appSecret\":\"secret2\",\"sm4Key\":\"32hexsm4key12345678abcdef\",\"expiresMin\":30}";

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json1);
        assertNotNull(SyTokenManager.INSTANCE.appMap.get("key-original"));
        assertNull(SyTokenManager.INSTANCE.appMap.get("key-rotated"));

        ReflectionTestUtils.invokeMethod(service, "applyConfigValue", json2);
        assertNull(SyTokenManager.INSTANCE.appMap.get("key-original"), "old key should be removed on rotation");
        assertNotNull(SyTokenManager.INSTANCE.appMap.get("key-rotated"), "new key should be present");
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

        assertEquals(30L, SyTokenManager.INSTANCE.expires, "default expiresMin should be 30 when not provided");
    }
}
