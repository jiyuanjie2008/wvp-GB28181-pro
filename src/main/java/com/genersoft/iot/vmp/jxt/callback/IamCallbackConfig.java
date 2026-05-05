package com.genersoft.iot.vmp.jxt.callback;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "jxt.iam-callback")
@Data
public class IamCallbackConfig {

    private String primaryKey;
    private String secondaryKey;
    private int activeKeyVersion = 1;
    private String iamBaseUrl;

    public String getActiveKey() {
        if (activeKeyVersion == 2 && secondaryKey != null && !secondaryKey.isEmpty()) {
            return secondaryKey;
        }
        return primaryKey;
    }

    public boolean isEnabled() {
        return primaryKey != null && !primaryKey.isEmpty()
                && iamBaseUrl != null && !iamBaseUrl.isEmpty();
    }
}
