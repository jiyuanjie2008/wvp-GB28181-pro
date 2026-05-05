package com.genersoft.iot.vmp.jxt.callback;

import com.genersoft.iot.vmp.gb28181.event.device.DeviceOfflineEvent;
import com.genersoft.iot.vmp.gb28181.event.device.DeviceOnlineEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class CallbackEventListener {

    @Autowired
    private IamCallbackClient iamCallbackClient;

    @Async
    @EventListener
    public void onDeviceOnline(DeviceOnlineEvent event) {
        if (event.getDevice() == null) {
            return;
        }
        String deviceId = event.getDevice().getDeviceId();
        Map<String, Object> payload = new HashMap<>();
        payload.put("ip", event.getDevice().getIp());
        payload.put("port", event.getDevice().getPort());
        payload.put("transport", event.getDevice().getTransport());

        iamCallbackClient.sendCallback("online", deviceId, payload);
    }

    @Async
    @EventListener
    public void onDeviceOffline(DeviceOfflineEvent event) {
        if (event.getDeviceIds() == null) {
            return;
        }
        for (String deviceId : event.getDeviceIds()) {
            iamCallbackClient.sendCallback("offline", deviceId, new HashMap<>());
        }
    }
}
