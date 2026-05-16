package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncPayloadSpecific;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DeviceIdentityTxServiceTest {

    private DeviceIdentityTxService txService;
    private DeviceIdentityMapper identityMapper;
    private DeviceMapper deviceMapper;
    private IRedisCatchStorage redisCatchStorage;

    @BeforeEach
    void setUp() {
        txService = new DeviceIdentityTxService();
        identityMapper = mock(DeviceIdentityMapper.class);
        deviceMapper = mock(DeviceMapper.class);
        redisCatchStorage = mock(IRedisCatchStorage.class);

        ReflectionTestUtils.setField(txService, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(txService, "deviceMapper", deviceMapper);
        ReflectionTestUtils.setField(txService, "redisCatchStorage", redisCatchStorage);
    }

    private IamSyncRequest validRequest() {
        IamSyncRequest req = new IamSyncRequest();
        req.setTargetDeviceId("34020000001320000001");
        IamSyncPayloadSpecific payload = new IamSyncPayloadSpecific();
        payload.setDeviceName("TestCamera");
        payload.setSipHa1("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        payload.setRealm("3502000000");
        payload.setStreamMode("TCP-PASSIVE");
        payload.setCharset("GB2312");
        payload.setHeartbeatInterval(60);
        payload.setHeartbeatCount(3);
        req.setPayloadSpecific(payload);
        return req;
    }

    @Test
    void newDevice_insertsWithDefaults() {
        when(deviceMapper.getDeviceByDeviceId("34020000001320000001")).thenReturn(null);
        when(identityMapper.insertDevice(any(Device.class))).thenReturn(1);

        DeviceIdentityService.DeviceIdentityResult result = txService.doRegister(validRequest());

        assertEquals(0, result.code());
        assertTrue(result.created());

        verify(identityMapper).insertDevice(argThat((Device device) ->
                "34020000001320000001".equals(device.getDeviceId()) &&
                "TestCamera".equals(device.getName()) &&
                "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4".equals(device.getSipHa1()) &&
                "TCP-PASSIVE".equals(device.getStreamMode()) &&
                "GB2312".equals(device.getCharset()) &&
                Boolean.FALSE.equals(device.getDisabled()) &&
                Boolean.TRUE.equals(device.getActivated()) &&
                !device.isOnLine()
        ));
        verify(redisCatchStorage).updateDevice(any(Device.class));
    }

    @Test
    void existingDevice_updateOnlyIamFields() {
        Device existing = new Device();
        existing.setDeviceId("34020000001320000001");
        existing.setIp("192.168.1.100");
        existing.setPort(5060);
        existing.setHostAddress("192.168.1.100:5060");
        existing.setTransport("UDP");
        existing.setOnLine(true);
        existing.setExpires(3600);
        existing.setServerId("server-1");

        when(deviceMapper.getDeviceByDeviceId("34020000001320000001")).thenReturn(existing);
        when(identityMapper.updateDevice(any(Device.class))).thenReturn(1);

        DeviceIdentityService.DeviceIdentityResult result = txService.doRegister(validRequest());

        assertEquals(0, result.code());
        assertFalse(result.created());

        verify(identityMapper).updateDevice(argThat((Device device) ->
                "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4".equals(device.getSipHa1()) &&
                "TestCamera".equals(device.getName()) &&
                "192.168.1.100".equals(device.getIp()) &&
                5060 == device.getPort() &&
                "192.168.1.100:5060".equals(device.getHostAddress()) &&
                "UDP".equals(device.getTransport()) &&
                device.isOnLine() &&
                3600 == device.getExpires() &&
                "server-1".equals(device.getServerId())
        ));
        verify(redisCatchStorage).updateDevice(any(Device.class));
    }

    @Test
    void existingDevice_nullPayloadFields_preserveExistingValues() {
        Device existing = new Device();
        existing.setDeviceId("34020000001320000001");
        existing.setName("OriginalName");
        existing.setCharset("UTF-8");
        existing.setStreamMode("UDP");
        existing.setIp("10.0.0.1");
        existing.setPort(5060);

        when(deviceMapper.getDeviceByDeviceId("34020000001320000001")).thenReturn(existing);
        when(identityMapper.updateDevice(any(Device.class))).thenReturn(1);

        IamSyncRequest req = validRequest();
        req.getPayloadSpecific().setDeviceName(null);
        req.getPayloadSpecific().setCharset(null);
        req.getPayloadSpecific().setStreamMode(null);

        txService.doRegister(req);

        verify(identityMapper).updateDevice(argThat((Device device) ->
                "OriginalName".equals(device.getName()) &&
                "UTF-8".equals(device.getCharset()) &&
                "UDP".equals(device.getStreamMode()) &&
                "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4".equals(device.getSipHa1()) &&
                "10.0.0.1".equals(device.getIp())
        ));
    }

    @Test
    void existingDevice_sipHa1IsAlwaysUpdated() {
        Device existing = new Device();
        existing.setDeviceId("34020000001320000001");
        existing.setSipHa1("old-ha1-value-000000000000000000000000000000");

        when(deviceMapper.getDeviceByDeviceId("34020000001320000001")).thenReturn(existing);
        when(identityMapper.updateDevice(any(Device.class))).thenReturn(1);

        txService.doRegister(validRequest());

        verify(identityMapper).updateDevice(argThat((Device device) ->
                "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4".equals(device.getSipHa1())
        ));
    }

    @Test
    void newDevice_usesDefaultsWhenPayloadFieldsNull() {
        when(deviceMapper.getDeviceByDeviceId("34020000001320000001")).thenReturn(null);
        when(identityMapper.insertDevice(any(Device.class))).thenReturn(1);

        IamSyncRequest req = validRequest();
        req.getPayloadSpecific().setStreamMode(null);
        req.getPayloadSpecific().setCharset(null);
        req.getPayloadSpecific().setHeartbeatInterval(null);
        req.getPayloadSpecific().setHeartbeatCount(null);

        txService.doRegister(req);

        verify(identityMapper).insertDevice(argThat((Device device) ->
                "TCP-PASSIVE".equals(device.getStreamMode()) &&
                "GB2312".equals(device.getCharset()) &&
                device.getHeartBeatInterval() == 60 &&
                device.getHeartBeatCount() == 3
        ));
    }
}
