package com.genersoft.iot.vmp.jxt.identity.auth;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import gov.nist.javax.sip.message.SIPRequest;

public interface DeviceAuthStrategy {
    int priority();
    AuthResult authenticate(Device device, SIPRequest request);
}
