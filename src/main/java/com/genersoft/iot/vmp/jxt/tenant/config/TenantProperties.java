package com.genersoft.iot.vmp.jxt.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jxt.tenant", ignoreInvalidFields = true)
@Order(0)
@Data
public class TenantProperties {

    private String code;
}
