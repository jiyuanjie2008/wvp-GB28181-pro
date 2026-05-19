package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.jxt.identity.auth.AuthResult;
import com.genersoft.iot.vmp.jxt.identity.auth.DeviceAuthStrategyChain;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.header.AuthorizationHeader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * D4: Integration tests for RegisterRequestProcessor helper methods.
 * Tests authenticateWithStrategyChain and authenticateWithLegacyPath directly.
 * Helper methods are package-private for test access.
 */
class RegisterRequestProcessorAuthTest {

    private RegisterRequestProcessor processor;
    private DeviceAuthStrategyChain chain;
    private DigestServerAuthenticationHelper digestHelper;
    private SipConfig sipConfig;

    @BeforeEach
    void setUp() {
        processor = new RegisterRequestProcessor();
        // Mock the strategy chain — we test processor logic, not strategy internals
        chain = mock(DeviceAuthStrategyChain.class);
        digestHelper = mock(DigestServerAuthenticationHelper.class);

        sipConfig = new SipConfig();
        sipConfig.setDomain("3402000000");
        sipConfig.setPassword(null);

        ReflectionTestUtils.setField(processor, "strategyChain", chain);
        ReflectionTestUtils.setField(processor, "sipConfig", sipConfig);
    }

    // --- authenticateWithStrategyChain tests ---

    @Test
    void strategyChain_ha1Device_authSuccess() throws Exception {
        Device device = new Device();
        device.setDeviceId("35020000001320000075");
        device.setSipHa1("011b6698544b88130d7b626327ac15f8");

        SIPRequest request = mock(SIPRequest.class);
        when(chain.authenticate(device, request)).thenReturn(AuthResult.SUCCESS);

        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_ha1Device_authFail() throws Exception {
        Device device = new Device();
        device.setDeviceId("35020000001320000075");
        device.setSipHa1("011b6698544b88130d7b626327ac15f8");

        SIPRequest request = mock(SIPRequest.class);
        when(chain.authenticate(device, request)).thenReturn(AuthResult.FAIL);

        assertFalse(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_passwordDevice_plaintextSuccess() throws Exception {
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword("camera123");

        SIPRequest request = mock(SIPRequest.class);
        when(chain.authenticate(device, request)).thenReturn(AuthResult.SUCCESS);

        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_noCredentials_noGlobalPassword_noAuth() throws Exception {
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        SIPRequest request = mock(SIPRequest.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(null);
        // Chain skips because device has no ha1 and no password
        when(chain.authenticate(device, request)).thenReturn(AuthResult.SKIP);

        // No global password, no auth header → true (no auth required)
        assertTrue(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_allSkip_noGlobalPassword_authHeaderPresent_rejects() throws Exception {
        // D6: device sent Authorization header but all strategies SKIP and no global password
        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);
        when(chain.authenticate(device, request)).thenReturn(AuthResult.SKIP);

        // Auth header present but no strategy verified → reject
        assertFalse(processor.authenticateWithStrategyChain(request, device));
    }

    @Test
    void strategyChain_nullDevice_globalPassword_authHeaderPresent() throws Exception {
        // null device → chain not called → global password fallback
        // But global password path creates new DigestServerAuthenticationHelper internally.
        // With mock request (no real auth header), doAuthenticatePlainTextPassword returns false.
        // So we test that the method doesn't throw and returns false.
        sipConfig.setPassword("global123");

        SIPRequest request = mock(SIPRequest.class);
        AuthorizationHeader authHead = mock(AuthorizationHeader.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(authHead);

        // The method creates new DigestServerAuthenticationHelper internally,
        // which will call doAuthenticatePlainTextPassword on a mock request → false
        assertFalse(processor.authenticateWithStrategyChain(request, null));
    }

    @Test
    void strategyChain_nullDevice_noGlobalPassword_noAuth() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(null);

        // null device, no global password, no auth header → no auth required
        assertTrue(processor.authenticateWithStrategyChain(request, null));
    }

    @Test
    void strategyChain_allSkip_globalPassword_noAuthHeader_noAuth() throws Exception {
        // All strategies skip, global password is set, but no Authorization header
        // → method checks global password, creates real DigestServerAuthenticationHelper
        // → doAuthenticatePlainTextPassword on mock request returns false (no real auth header)
        // → then falls through to auth header check → null → returns true
        // Actually: global password path runs first. Real helper returns false.
        // Then authHead != null check... but global password path already returned false.
        sipConfig.setPassword("global123");

        Device device = new Device();
        device.setDeviceId("34020000001320000001");
        device.setSipHa1(null);
        device.setPassword(null);

        SIPRequest request = mock(SIPRequest.class);
        when(request.getHeader(AuthorizationHeader.NAME)).thenReturn(null);
        when(chain.authenticate(device, request)).thenReturn(AuthResult.SKIP);

        // Global password path creates real helper → doAuthenticatePlainTextPassword with null auth header → false
        assertFalse(processor.authenticateWithStrategyChain(request, device));
    }

    // --- authenticateWithLegacyPath tests ---

    @Test
    void legacyPath_noPassword_returnsTrue() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        assertTrue(processor.authenticateWithLegacyPath(request, null));
    }

    @Test
    void legacyPath_emptyPassword_returnsTrue() throws Exception {
        SIPRequest request = mock(SIPRequest.class);
        assertTrue(processor.authenticateWithLegacyPath(request, ""));
    }

    @Test
    void legacyPath_passwordPresent_createsRealHelper_noException() throws Exception {
        // authenticateWithLegacyPath creates new DigestServerAuthenticationHelper internally.
        // With a mock request, doAuthenticatePlainTextPassword returns false (no real auth header).
        // Test verifies no exception is thrown and returns boolean.
        SIPRequest request = mock(SIPRequest.class);
        boolean result = processor.authenticateWithLegacyPath(request, "camera123");
        // Real helper will return false because mock request has no real Authorization header
        assertFalse(result);
    }

    @Test
    void legacyPath_passwordPresent_returnsBoolean() throws Exception {
        // Verify the method returns a boolean without throwing for any password value
        SIPRequest request = mock(SIPRequest.class);
        assertDoesNotThrow(() -> processor.authenticateWithLegacyPath(request, "wrong"));
    }
}
