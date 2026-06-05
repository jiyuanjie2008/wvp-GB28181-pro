package com.genersoft.iot.vmp.jxt.tenant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jxt.etcd", ignoreInvalidFields = true)
@Data
public class EtcdProperties {

    private String endpoints;

    private String namespace;
}
