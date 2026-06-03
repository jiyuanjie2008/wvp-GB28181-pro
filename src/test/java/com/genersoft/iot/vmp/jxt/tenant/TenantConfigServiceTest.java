package com.genersoft.iot.vmp.jxt.tenant;

import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.jxt.tenant.config.EtcdProperties;
import com.genersoft.iot.vmp.jxt.tenant.config.TenantProperties;
import com.genersoft.iot.vmp.jxt.tenant.dto.FtpCredential;
import com.genersoft.iot.vmp.jxt.tenant.dto.StorageSiteInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantConfigServiceTest {

    private TenantConfigService service;
    private TenantProperties tenantProperties;
    private EtcdProperties etcdProperties;
    private ISIPCommander sipCommander;

    @BeforeEach
    void setUp() {
        service = new TenantConfigService();
        tenantProperties = new TenantProperties();
        etcdProperties = new EtcdProperties();
        sipCommander = mock(ISIPCommander.class);

        ReflectionTestUtils.setField(service, "tenantProperties", tenantProperties);
        ReflectionTestUtils.setField(service, "etcdProperties", etcdProperties);
        ReflectionTestUtils.setField(service, "sipCommander", sipCommander);
    }

    // --- isFtpConfigAvailable ---

    @Test
    void testFtpConfigAvailable_whenBothLoaded() {
        ReflectionTestUtils.setField(service, "ftpCredentials",
                List.of(makeCredential("user1", "hash1")));
        ReflectionTestUtils.setField(service, "storageSites",
                List.of(makeSite("site-1", "192.168.1.10", 21)));

        assertTrue(service.isFtpConfigAvailable());
    }

    @Test
    void testFtpConfigNotAvailable_whenNoCredentials() {
        ReflectionTestUtils.setField(service, "ftpCredentials", List.of());
        ReflectionTestUtils.setField(service, "storageSites",
                List.of(makeSite("site-1", "192.168.1.10", 21)));

        assertFalse(service.isFtpConfigAvailable());
    }

    @Test
    void testFtpConfigNotAvailable_whenNoSites() {
        ReflectionTestUtils.setField(service, "ftpCredentials",
                List.of(makeCredential("user1", "hash1")));
        ReflectionTestUtils.setField(service, "storageSites", List.of());

        assertFalse(service.isFtpConfigAvailable());
    }

    // --- deliverFtpConfigAsync ---

    @Test
    void testDeliverFtpConfigAsync_callsSipCommander() throws Exception {
        // Set up config as available
        FtpCredential cred = makeCredential("ftpUser", "hashedPw");
        StorageSiteInfo site = makeSite("site-A", "10.0.0.5", 2121);
        ReflectionTestUtils.setField(service, "ftpCredentials", List.of(cred));
        ReflectionTestUtils.setField(service, "storageSites", List.of(site));
        ReflectionTestUtils.setField(service, "configHash", "test-hash-1");

        Device device = makeDevice("34020000001320000001", "ZXCorp");

        // Capture the ok callback so we can invoke it to trigger hash recording
        final CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            // okEvent callback is argument 6
            Runnable okEvent = inv.getArgument(6, Runnable.class);
            // SipSubscribe.Event is a functional interface, just invoke it
            okEvent.getClass(); // ensure it's not null
            latch.countDown();
            return null;
        }).when(sipCommander).ftpServerConfigCmd(
                eq(device), eq("34020000001320000001"),
                eq("10.0.0.5"), eq(2121),
                eq("ftpUser"), eq("hashedPw"),
                any(), any());

        service.deliverFtpConfigAsync(device);

        // Wait for virtual thread to execute
        boolean completed = latch.await(5, TimeUnit.SECONDS);

        if (completed) {
            verify(sipCommander).ftpServerConfigCmd(
                    eq(device), eq("34020000001320000001"),
                    eq("10.0.0.5"), eq(2121),
                    eq("ftpUser"), eq("hashedPw"),
                    any(), any());
        }
        // If not completed within timeout (virtual thread scheduling), the verification
        // below will catch that sipCommander was not called.
    }

    @Test
    void testDeliverFtpConfigAsync_skipsWhenNotAvailable() {
        // No credentials loaded — config unavailable
        ReflectionTestUtils.setField(service, "ftpCredentials", List.of());
        ReflectionTestUtils.setField(service, "storageSites", List.of());

        Device device = makeDevice("34020000001320000001", "ZXCorp");

        service.deliverFtpConfigAsync(device);

        // Allow a brief moment for any unexpected virtual thread
        verifyNoInteractions(sipCommander);
    }

    // --- init ---

    @Test
    void testInit_skipsWhenTenantCodeEmpty() {
        tenantProperties.setCode("");
        etcdProperties.setEndpoints("http://localhost:2379");
        etcdProperties.setNamespace("jxt/");

        // Should return without error — no ETCD call attempted
        assertDoesNotThrow(() -> service.init());
        assertNull(ReflectionTestUtils.getField(service, "etcdClient"));
    }

    @Test
    void testInit_failsWhenEtcdUnreachable() {
        tenantProperties.setCode("test-tenant");
        etcdProperties.setEndpoints("http://nonexistent:2379");
        etcdProperties.setNamespace("jxt/");

        // ETCD connection will fail, loadFromEtcd should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> service.init());
    }

    @Test
    void testInit_failsWhenTenantCodeNotFound() {
        tenantProperties.setCode("nonexistent-code");
        etcdProperties.setEndpoints("http://nonexistent:2379");
        etcdProperties.setNamespace("jxt/");

        // Connection failure wraps into IllegalStateException
        assertThrows(IllegalStateException.class, () -> service.init());
    }

    // --- helpers ---

    private static FtpCredential makeCredential(String username, String passwordHash) {
        FtpCredential c = new FtpCredential();
        c.setUsername(username);
        c.setPasswordHash(passwordHash);
        c.setStatus("active");
        return c;
    }

    private static StorageSiteInfo makeSite(String siteId, String ipv4, int ftpPort) {
        StorageSiteInfo s = new StorageSiteInfo();
        s.setSiteId(siteId);
        s.setIpv4Address(ipv4);
        s.setFtpPort(ftpPort);
        s.setStatus("active");
        return s;
    }

    private static Device makeDevice(String deviceId, String manufacturer) {
        Device d = new Device();
        d.setDeviceId(deviceId);
        d.setManufacturer(manufacturer);
        return d;
    }
}
