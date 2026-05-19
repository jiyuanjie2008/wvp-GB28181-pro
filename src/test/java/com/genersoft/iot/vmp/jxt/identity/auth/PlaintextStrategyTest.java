package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// D5: Tests use anonymous subclass to override createHelper() instead of @Autowired field
class PlaintextStrategyTest {

    private PlaintextStrategy strategy;
    private DigestServerAuthenticationHelper digestHelper;
    private Device device;

    @BeforeEach
    void setUp() {
        digestHelper = mock(DigestServerAuthenticationHelper.class);
        strategy = new PlaintextStrategy() {
            @Override
            DigestServerAuthenticationHelper createHelper() { return digestHelper; }
        };
        device = new Device();
        device.setDeviceId("34020000001320000001");
    }

    @Test
    void priorityIs2() {
        assertEquals(2, strategy.priority());
    }

    @Test
    void passwordNull_returnsSKIP() {
        device.setPassword(null);
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void passwordEmpty_returnsSKIP() {
        device.setPassword("");
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void digestSuccess_returnsSUCCESS() throws Exception {
        device.setPassword("camera123");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("camera123"))).thenReturn(true);
        assertEquals(AuthResult.SUCCESS, strategy.authenticate(device, request));
    }

    @Test
    void digestFail_returnsFAIL() throws Exception {
        device.setPassword("camera123");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), eq("camera123"))).thenReturn(false);
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }

    @Test
    void digestThrows_returnsFAIL() throws Exception {
        device.setPassword("camera123");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticatePlainTextPassword(any(), anyString())).thenThrow(new RuntimeException("test error"));
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }
}
