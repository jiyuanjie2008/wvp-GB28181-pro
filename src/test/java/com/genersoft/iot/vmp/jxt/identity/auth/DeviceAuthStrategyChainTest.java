package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceAuthStrategyChainTest {

    private final Device device = new Device();
    private final SIPRequest request = mock(SIPRequest.class);

    @Test
    void strategiesSortedByPriority() {
        DeviceAuthStrategy low = mock(DeviceAuthStrategy.class);
        when(low.priority()).thenReturn(5);
        DeviceAuthStrategy high = mock(DeviceAuthStrategy.class);
        when(high.priority()).thenReturn(1);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(low, high));
        when(high.authenticate(any(), any())).thenReturn(AuthResult.SUCCESS);
        chain.authenticate(device, request);

        verify(high).authenticate(device, request);
        verify(low, never()).authenticate(any(), any());
    }

    @Test
    void firstNonSKIPStopsChain_returnsSUCCESS() {
        DeviceAuthStrategy s1 = mock(DeviceAuthStrategy.class);
        when(s1.priority()).thenReturn(1);
        when(s1.authenticate(any(), any())).thenReturn(AuthResult.SKIP);

        DeviceAuthStrategy s2 = mock(DeviceAuthStrategy.class);
        when(s2.priority()).thenReturn(2);
        when(s2.authenticate(any(), any())).thenReturn(AuthResult.SUCCESS);

        DeviceAuthStrategy s3 = mock(DeviceAuthStrategy.class);
        when(s3.priority()).thenReturn(3);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(s1, s2, s3));
        AuthResult result = chain.authenticate(device, request);

        assertEquals(AuthResult.SUCCESS, result);
        verify(s1).authenticate(device, request);
        verify(s2).authenticate(device, request);
        verify(s3, never()).authenticate(any(), any());
    }

    @Test
    void allSKIP_returnsSKIP() {
        DeviceAuthStrategy s1 = mock(DeviceAuthStrategy.class);
        when(s1.priority()).thenReturn(1);
        when(s1.authenticate(any(), any())).thenReturn(AuthResult.SKIP);

        DeviceAuthStrategy s2 = mock(DeviceAuthStrategy.class);
        when(s2.priority()).thenReturn(2);
        when(s2.authenticate(any(), any())).thenReturn(AuthResult.SKIP);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(s1, s2));
        assertEquals(AuthResult.SKIP, chain.authenticate(device, request));
    }

    @Test
    void emptyStrategies_returnsSKIP() {
        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of());
        assertEquals(AuthResult.SKIP, chain.authenticate(device, request));
    }

    @Test
    void firstStrategyFAIL_stopsChain() {
        DeviceAuthStrategy s1 = mock(DeviceAuthStrategy.class);
        when(s1.priority()).thenReturn(1);
        when(s1.authenticate(any(), any())).thenReturn(AuthResult.FAIL);

        DeviceAuthStrategy s2 = mock(DeviceAuthStrategy.class);
        when(s2.priority()).thenReturn(2);

        DeviceAuthStrategyChain chain = new DeviceAuthStrategyChain(List.of(s1, s2));
        assertEquals(AuthResult.FAIL, chain.authenticate(device, request));
        verify(s2, never()).authenticate(any(), any());
    }
}
