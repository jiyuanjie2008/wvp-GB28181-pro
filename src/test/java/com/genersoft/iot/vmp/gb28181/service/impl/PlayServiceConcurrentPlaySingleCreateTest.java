package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 同通道并发冷点播仅创建一次 invite 的行为锁定(2026-11-14 S8 R=3 定案):
 * 50 play 同发打死流时,play() 的"查 invite 缓存→注册新 invite"之间无原子性
 * (跨 SSRC 取池 + ZLM HTTP 开端口,宽窗几十 ms),竞态漏出双发 invite
 * (实测 INVITE 527 vs 下限 291,全部唯一 Call-ID=非重传)→ 输家会话被
 * updateInviteInfo 覆盖后从 stream 键不可达 → 永不 BYE、永不还号
 * (SSRC 泄漏 63% 会话、孤儿流 6→9→12 跨轮不死)。
 * 修复=创建段按通道串行化,输家必命中既有"已经请求中,等待结果"路径(14223 次实战验证)。
 * 本测试用带 50ms 延迟的 openGbRTPServerForPlay 模拟 ZLM HTTP RTT 放宽竞态窗:
 * 修复前红(创建数>1),修复后绿(创建数恰 1,等待注册数=并发数-1)。
 */
class PlayServiceConcurrentPlaySingleCreateTest {

    private static final String DEVICE_ID = "34020000001320000001";
    private static final String CHANNEL_GB_ID = "34020000001320000002";
    private static final Integer CHANNEL_ID = 1;
    private static final String STREAM_ID = DEVICE_ID + "_" + CHANNEL_GB_ID;

    private PlayServiceImpl service;
    private IInviteStreamService inviteStreamService;
    private IReceiveRtpServerService receiveRtpServerService;
    private Map<Integer, InviteInfo> inviteCache;
    private MediaServer mediaServer;

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        inviteStreamService = mock(IInviteStreamService.class);
        receiveRtpServerService = mock(IReceiveRtpServerService.class);
        ISIPCommander cmder = mock(ISIPCommander.class);
        SSRCFactory ssrcFactory = mock(SSRCFactory.class);
        IRedisCatchStorage redisCatchStorage = mock(IRedisCatchStorage.class);
        IDeviceChannelService deviceChannelService = mock(IDeviceChannelService.class);

        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");
        ReflectionTestUtils.setField(userSetting, "playTimeout", 30000);
        ReflectionTestUtils.setField(userSetting, "recordSip", false);

        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtpServerService);
        ReflectionTestUtils.setField(service, "cmder", cmder);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "redisCatchStorage", redisCatchStorage);
        ReflectionTestUtils.setField(service, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);

        Device device = mock(Device.class);
        when(device.getServerId()).thenReturn("000000");
        when(device.getDeviceId()).thenReturn(DEVICE_ID);
        when(device.getStreamMode()).thenReturn("UDP");
        when(device.isSsrcCheck()).thenReturn(false);
        when(redisCatchStorage.getDevice(DEVICE_ID)).thenReturn(device);

        DeviceChannel channel = mock(DeviceChannel.class);
        when(channel.getId()).thenReturn(CHANNEL_ID);
        when(channel.getDeviceId()).thenReturn(CHANNEL_GB_ID);
        when(channel.isHasAudio()).thenReturn(false);
        when(deviceChannelService.getOneForSource(DEVICE_ID, CHANNEL_GB_ID)).thenReturn(channel);

        MediaServer mediaServer = mock(MediaServer.class);
        when(mediaServer.getId()).thenReturn("media-1");
        when(mediaServer.getSdpIp()).thenReturn("172.24.0.3");
        this.mediaServer = mediaServer;

        // invite 缓存语义化:注册前查空、注册后可查(镜像 redis 行为,输家凭此走等待路径)
        inviteCache = new ConcurrentHashMap<>();
        when(inviteStreamService.getInviteInfoByDeviceAndChannel(any(), eq(CHANNEL_ID)))
                .thenAnswer(inv -> inviteCache.get(CHANNEL_ID));
        doAnswer(inv -> {
            inviteCache.put(CHANNEL_ID, inv.getArgument(0));
            return null;
        }).when(inviteStreamService).updateInviteInfo(any());
        doAnswer(inv -> {
            inviteCache.remove(CHANNEL_ID);
            return null;
        }).when(inviteStreamService).removeInviteInfoByDeviceAndChannel(any(), eq(CHANNEL_ID));

        // 50ms 延迟=真实 ZLM HTTP RTT,放宽竞态窗使未修复实现稳定现形
        when(receiveRtpServerService.openGbRTPServerForPlay(any(), any(), any(), isNull(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new SSRCInfo(10003, "0200000001", MediaStreamUtil.RTP_APP, STREAM_ID);
                });
    }

    @Test
    void concurrentColdPlayCreatesSingleInvite() throws Exception {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    service.play(mediaServer, DEVICE_ID, CHANNEL_GB_ID, null, (code, msg, data) -> {});
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();

        verify(receiveRtpServerService, times(1))
                .openGbRTPServerForPlay(any(), any(), any(), isNull(), anyBoolean(), any());
        verify(inviteStreamService, times(threads - 1))
                .once(eq(InviteSessionType.PLAY), eq(CHANNEL_ID), isNull(), any());
    }
}
