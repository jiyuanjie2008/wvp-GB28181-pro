package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.auth.DigestServerAuthenticationHelper;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.security.NoSuchAlgorithmException;

@Slf4j
@Component
@ConditionalOnProperty(value = "jxt.identity.strategy.ha1-enabled", havingValue = "true", matchIfMissing = true)
public class Ha1Strategy implements DeviceAuthStrategy {

    // D5: NOT a singleton bean — MessageDigest is NOT thread-safe.
    // Each call creates a new DigestServerAuthenticationHelper instance.
    DigestServerAuthenticationHelper createHelper() {
        try {
            return new DigestServerAuthenticationHelper();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public AuthResult authenticate(Device device, SIPRequest request) {
        if (ObjectUtils.isEmpty(device.getSipHa1())) {
            return AuthResult.SKIP;
        }
        try {
            boolean ok = createHelper().doAuthenticateHashedPassword(request, device.getSipHa1());
            if (ok) {
                log.debug("Ha1Strategy: device {} authenticated via HA1 digest", device.getDeviceId());
                return AuthResult.SUCCESS;
            }
            log.warn("Ha1Strategy: device {} HA1 digest verification failed", device.getDeviceId());
            return AuthResult.FAIL;
        } catch (Exception e) {
            log.error("Ha1Strategy: error authenticating device {}", device.getDeviceId(), e);
            return AuthResult.FAIL;
        }
    }
}
