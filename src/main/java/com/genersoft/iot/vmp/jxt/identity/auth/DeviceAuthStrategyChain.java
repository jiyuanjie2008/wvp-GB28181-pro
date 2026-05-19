package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class DeviceAuthStrategyChain {

    private final List<DeviceAuthStrategy> strategies;

    public DeviceAuthStrategyChain(List<DeviceAuthStrategy> strategies) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparingInt(DeviceAuthStrategy::priority))
                .toList();
        log.info("DeviceAuthStrategyChain initialized with {} strategies: {}",
                this.strategies.size(),
                this.strategies.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    public AuthResult authenticate(Device device, SIPRequest request) {
        for (DeviceAuthStrategy strategy : strategies) {
            AuthResult result = strategy.authenticate(device, request);
            if (result != AuthResult.SKIP) {
                return result;
            }
        }
        return AuthResult.SKIP;
    }
}
