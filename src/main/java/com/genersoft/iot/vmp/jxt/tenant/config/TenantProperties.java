package com.genersoft.iot.vmp.jxt.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jxt.tenant", ignoreInvalidFields = true)
@Data
public class TenantProperties {

    private String code;
}
