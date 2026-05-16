package com.genersoft.iot.vmp.jxt.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jxt.identity")
public class IdentityConfig {
    private boolean enabled = true;
    private ControllerConfig controller = new ControllerConfig();
    private StrategyConfig strategy = new StrategyConfig();
    private IdempotencyConfig idempotency = new IdempotencyConfig();

    @Data
    public static class ControllerConfig {
        private boolean enabled = true;
    }

    @Data
    public static class StrategyConfig {
        private boolean ha1Enabled = true;
        private boolean plaintextEnabled = true;
    }

    @Data
    public static class IdempotencyConfig {
        private int cleanupDays = 7;
    }
}
