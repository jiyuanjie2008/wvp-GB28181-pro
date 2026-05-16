package com.genersoft.iot.vmp.jxt.identity.service;

import com.genersoft.iot.vmp.jxt.identity.config.IdentityConfig;
import com.genersoft.iot.vmp.jxt.identity.dto.IamSyncRequest;
import com.genersoft.iot.vmp.jxt.identity.mapper.DeviceIdentityMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DeviceIdentityService {

    @Autowired
    private DeviceIdentityMapper identityMapper;

    @Autowired
    private DeviceIdentityTxService txService;

    @Autowired
    private IdentityConfig identityConfig;

    public DeviceIdentityResult register(IamSyncRequest request) {
        String key = request.getIdempotencyKey();
        String deviceId = request.getTargetDeviceId();

        // Idempotency: INSERT → DuplicateKeyException = already processed (D4 Option B)
        try {
            identityMapper.tryInsertIdempotencyLog(key, request.getOperation(), deviceId);
        } catch (DuplicateKeyException e) {
            log.info("Idempotent hit: key={}, device={}", key, deviceId);
            return DeviceIdentityResult.ok(deviceId, false);
        }

        try {
            return txService.doRegister(request);
        } catch (Exception e) {
            identityMapper.deleteIdempotencyLog(key);
            log.error("Register failed for device {}, key={}: {}", deviceId, key, e.getMessage());
            throw e;
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupIdempotencyLog() {
        int days = identityConfig.getIdempotency().getCleanupDays();
        int deleted = identityMapper.cleanOldEntries(days);
        if (deleted > 0) {
            log.info("Cleaned {} idempotency log entries older than {} days", deleted, days);
        }
    }

    public record DeviceIdentityResult(int code, String msg, String deviceId, Boolean created) {
        public static DeviceIdentityResult ok(String deviceId, boolean created) {
            return new DeviceIdentityResult(0, "success", deviceId, created);
        }

        public static DeviceIdentityResult fail(int code, String msg) {
            return new DeviceIdentityResult(code, msg, null, null);
        }
    }
}
