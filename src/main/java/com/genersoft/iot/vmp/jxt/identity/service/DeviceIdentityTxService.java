package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncPayloadSpecific;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;

@Slf4j
@Service
public class DeviceIdentityTxService {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> VALID_DEVICE_TYPES = Set.of("BWC", "VEHICLE", "FIXED_CAMERA", "DRONE");

    @Autowired
    private DeviceIdentityMapper identityMapper;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Transactional
    public DeviceIdentityService.DeviceIdentityResult doRegister(IamSyncRequest request) {
        String deviceId = request.getTargetDeviceId();
        IamSyncPayloadSpecific payload = request.getPayloadSpecific();
        Device device = deviceMapper.getDeviceByDeviceId(deviceId);
        boolean created;
        if (device == null) {
            device = buildNewDevice(deviceId, payload);
            identityMapper.insertDevice(device);
            created = true;
        } else {
            applyIamFields(device, payload);
            identityMapper.updateDevice(device);
            created = false;
        }
        // Sync Redis cache so W04 SIP REGISTER reads fresh HA1 data
        redisCatchStorage.updateDevice(device);
        return DeviceIdentityService.DeviceIdentityResult.ok(deviceId, created);
    }

    private Device buildNewDevice(String deviceId, IamSyncPayloadSpecific payload) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setName(payload.getDeviceName());
        device.setSipHa1(payload.getSipHa1());
        device.setStreamMode(payload.getStreamMode() != null ? payload.getStreamMode() : "TCP-PASSIVE");
        device.setCharset(payload.getCharset() != null ? payload.getCharset() : "GB2312");
        device.setMediaServerId(payload.getMediaServerId() != null ? payload.getMediaServerId() : "auto");
        device.setSsrcCheck(payload.getSsrcCheck() != null ? payload.getSsrcCheck() : false);
        device.setGeoCoordSys(payload.getGeoCoordSys() != null ? payload.getGeoCoordSys() : "WGS84");
        device.setAsMessageChannel(payload.getAsMessageChannel() != null ? payload.getAsMessageChannel() : false);
        device.setBroadcastPushAfterAck(payload.getBroadcastPushAfterAck() != null ? payload.getBroadcastPushAfterAck() : false);
        device.setHeartBeatInterval(payload.getHeartbeatInterval() != null ? payload.getHeartbeatInterval() : 60);
        device.setHeartBeatCount(payload.getHeartbeatCount() != null ? payload.getHeartbeatCount() : 3);
        device.setDisabled(false);
        device.setActivated(true);
        device.setPassword(null);
        device.setExpires(3600);
        device.setOnLine(false);
        String now = LocalDateTime.now().format(DTF);
        device.setCreateTime(now);
        device.setUpdateTime(now);
        device.setServerId("auto");
        device.setDeviceType(payload.getDeviceType());
        if (payload.getDeviceType() != null && !VALID_DEVICE_TYPES.contains(payload.getDeviceType())) {
            log.warn("IAM sync: unexpected deviceType={} for device={}, expected one of {}",
                    payload.getDeviceType(), deviceId, VALID_DEVICE_TYPES);
        }
        if (payload.getSdpIp() != null) {
            device.setSdpIp(payload.getSdpIp());
        }
        return device;
    }

    private void applyIamFields(Device device, IamSyncPayloadSpecific payload) {
        device.setSipHa1(payload.getSipHa1());
        if (payload.getDeviceName() != null) device.setName(payload.getDeviceName());
        if (payload.getCharset() != null) device.setCharset(payload.getCharset());
        if (payload.getMediaServerId() != null) device.setMediaServerId(payload.getMediaServerId());
        if (payload.getStreamMode() != null) device.setStreamMode(payload.getStreamMode());
        if (payload.getSsrcCheck() != null) device.setSsrcCheck(payload.getSsrcCheck());
        if (payload.getGeoCoordSys() != null) device.setGeoCoordSys(payload.getGeoCoordSys());
        if (payload.getAsMessageChannel() != null) device.setAsMessageChannel(payload.getAsMessageChannel());
        if (payload.getBroadcastPushAfterAck() != null) device.setBroadcastPushAfterAck(payload.getBroadcastPushAfterAck());
        if (payload.getHeartbeatInterval() != null) device.setHeartBeatInterval(payload.getHeartbeatInterval());
        if (payload.getHeartbeatCount() != null) device.setHeartBeatCount(payload.getHeartbeatCount());
        if (payload.getSdpIp() != null) device.setSdpIp(payload.getSdpIp());
        if (payload.getDeviceType() != null) {
            device.setDeviceType(payload.getDeviceType());
            if (!VALID_DEVICE_TYPES.contains(payload.getDeviceType())) {
                log.warn("IAM sync update: unexpected deviceType={} for device={}",
                        payload.getDeviceType(), device.getDeviceId());
            }
        }
        device.setUpdateTime(LocalDateTime.now().format(DTF));
    }
}
