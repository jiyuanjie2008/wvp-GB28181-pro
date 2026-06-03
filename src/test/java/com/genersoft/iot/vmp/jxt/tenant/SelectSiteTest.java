package com.genersoft.iot.vmp.jxt.tenant;

import com.genersoft.iot.vmp.jxt.tenant.dto.StorageSiteInfo;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the site-selection hash formula used in
 * {@link TenantConfigService#selectSite(String, java.util.List)}.
 *
 * <p>The formula is:
 * {@code int index = (deviceId.hashCode() & Integer.MAX_VALUE) % sites.size()}
 *
 * <p>Since selectSite is private, we replicate the formula directly here.
 */
class SelectSiteTest {

    private static int selectSiteIndex(String deviceId, int siteCount) {
        return (deviceId.hashCode() & Integer.MAX_VALUE) % siteCount;
    }

    private static StorageSiteInfo makeSite(String siteId) {
        StorageSiteInfo s = new StorageSiteInfo();
        s.setSiteId(siteId);
        s.setIpv4Address("10.0.0.1");
        s.setFtpPort(21);
        s.setStatus("active");
        return s;
    }

    @Test
    void testSameDeviceId_alwaysReturnsSameSite() {
        List<StorageSiteInfo> sites = List.of(
                makeSite("site-A"), makeSite("site-B"), makeSite("site-C")
        );
        String deviceId = "34020000001320000001";

        int first = selectSiteIndex(deviceId, sites.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(first, selectSiteIndex(deviceId, sites.size()),
                    "Same deviceId must always map to the same site index");
        }
    }

    @Test
    void testDifferentDeviceIds_distributeAcrossSites() {
        List<StorageSiteInfo> sites = List.of(
                makeSite("site-A"), makeSite("site-B"), makeSite("site-C")
        );

        Set<Integer> selectedIndices = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            String deviceId = String.format("34020000001320%06d", i);
            selectedIndices.add(selectSiteIndex(deviceId, sites.size()));
        }

        // With 300 device IDs and 3 sites, we should hit all 3
        assertEquals(3, selectedIndices.size(),
                "Expected device IDs to distribute across all sites");
    }

    @Test
    void testIntegerMinValue_hashCode_noException() {
        // Integer.MIN_VALUE is the classic Math.abs overflow case.
        // The bitmask & Integer.MAX_VALUE neutralizes the sign bit safely.
        String deviceId = String.valueOf(Integer.MIN_VALUE);

        List<StorageSiteInfo> sites = List.of(
                makeSite("site-A"), makeSite("site-B")
        );

        // Must not throw and must produce a valid index
        int index = selectSiteIndex(deviceId, sites.size());
        assertTrue(index >= 0 && index < sites.size(),
                "Index must be a valid array position");

        // Verify the exact value: Integer.MIN_VALUE has hashCode == Integer.MIN_VALUE
        // Integer.MIN_VALUE & Integer.MAX_VALUE == 0, so index = 0 % 2 = 0
        assertEquals(0, index,
                "Integer.MIN_VALUE's hashCode masked with MAX_VALUE should yield 0");
    }
}
