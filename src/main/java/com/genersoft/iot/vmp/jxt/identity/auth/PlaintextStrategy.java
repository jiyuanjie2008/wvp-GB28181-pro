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
@ConditionalOnProperty(value = "jxt.identity.strategy.plaintext-enabled", havingValue = "true", matchIfMissing = true)
public class PlaintextStrategy implements DeviceAuthStrategy {

    // D5: NOT a singleton bean — MessageDigest is NOT thread-safe.
    DigestServerAuthenticationHelper createHelper() {
        try {
            return new DigestServerAuthenticationHelper();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public AuthResult authenticate(Device device, SIPRequest request) {
        String password = device.getPassword();
        if (ObjectUtils.isEmpty(password)) {
            return AuthResult.SKIP;
        }
        try {
            boolean ok = createHelper().doAuthenticatePlainTextPassword(request, password);
            if (ok) {
                log.debug("PlaintextStrategy: device {} authenticated via plaintext password", device.getDeviceId());
                return AuthResult.SUCCESS;
            }
            log.warn("PlaintextStrategy: device {} password verification failed", device.getDeviceId());
            return AuthResult.FAIL;
        } catch (Exception e) {
            log.error("PlaintextStrategy: error authenticating device {}", device.getDeviceId(), e);
            return AuthResult.FAIL;
        }
    }
}
