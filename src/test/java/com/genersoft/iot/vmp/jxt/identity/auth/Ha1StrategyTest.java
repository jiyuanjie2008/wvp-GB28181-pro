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
class Ha1StrategyTest {

    private Ha1Strategy strategy;
    private DigestServerAuthenticationHelper digestHelper;
    private Device device;

    @BeforeEach
    void setUp() {
        digestHelper = mock(DigestServerAuthenticationHelper.class);
        strategy = new Ha1Strategy() {
            @Override
            DigestServerAuthenticationHelper createHelper() { return digestHelper; }
        };
        device = new Device();
        device.setDeviceId("34020000001320000001");
    }

    @Test
    void priorityIs1() {
        assertEquals(1, strategy.priority());
    }

    @Test
    void sipHa1Null_returnsSKIP() {
        device.setSipHa1(null);
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void sipHa1Empty_returnsSKIP() {
        device.setSipHa1("");
        SIPRequest request = mock(SIPRequest.class);
        assertEquals(AuthResult.SKIP, strategy.authenticate(device, request));
        verifyNoInteractions(digestHelper);
    }

    @Test
    void digestSuccess_returnsSUCCESS() throws Exception {
        device.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticateHashedPassword(any(), eq("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))).thenReturn(true);
        assertEquals(AuthResult.SUCCESS, strategy.authenticate(device, request));
    }

    @Test
    void digestFail_returnsFAIL() throws Exception {
        device.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticateHashedPassword(any(), eq("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))).thenReturn(false);
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }

    @Test
    void digestThrows_returnsFAIL() throws Exception {
        device.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        SIPRequest request = mock(SIPRequest.class);
        when(digestHelper.doAuthenticateHashedPassword(any(), anyString())).thenThrow(new RuntimeException("test error"));
        assertEquals(AuthResult.FAIL, strategy.authenticate(device, request));
    }
}
