package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * stop() invite==null 分支的还号行为锁定(2026-11-03 fixverify 轮 4/100 泄漏定案):
 * 流注销/重注册循环链路会删 invite 但不还号,停播进本分支后若只清残留事务不还号,
 * SSRC 永久滞留池(20261102 P0 修复的"不重复还号"假设仅对超时路径成立)。
 * 护栏:该号已被其他在册会话持有(超时还号后被复用+事务重建窗口)时不得还号,防偷号。
 */
class PlayServiceStopResidualSsrcTest {

    private static final String STREAM = "34020000001320000001_34020000001320000002";
    private static final String SSRC = "0200000536";

    private PlayServiceImpl service;
    private IInviteStreamService inviteStreamService;
    private SipInviteSessionManager sessionManager;
    private SSRCFactory ssrcFactory;
    private IDeviceChannelService deviceChannelService;
    private Device device;
    private DeviceChannel channel;

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        inviteStreamService = mock(IInviteStreamService.class);
        sessionManager = mock(SipInviteSessionManager.class);
        ssrcFactory = mock(SSRCFactory.class);
        deviceChannelService = mock(IDeviceChannelService.class);
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");

        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);

        device = mock(Device.class);
        when(device.getServerId()).thenReturn("000000");
        when(device.getDeviceId()).thenReturn("34020000001320000001");
        channel = mock(DeviceChannel.class);
        when(channel.getId()).thenReturn(1);
        when(channel.getDeviceId()).thenReturn("34020000001320000002");

        // 公共场景:invite 已失(被流注销/循环链路删除),残留事务仍在
        when(inviteStreamService.getInviteInfo(any(), any(), any())).thenReturn(null);
        SsrcTransaction residual = new SsrcTransaction();
        residual.setApp("rtp");
        residual.setStream(STREAM);
        residual.setSsrc(SSRC);
        residual.setAllocatedSsrc(SSRC);
        residual.setMediaServerId("polaris");
        when(sessionManager.getSsrcTransactionByStream(anyString(), eq(STREAM))).thenReturn(residual);
        when(sessionManager.getAll()).thenReturn(Collections.emptyList());
    }

    @Test
    void stopReleasesResidualSsrc_whenInviteLostAndNoOtherOwner() {
        service.stop(InviteSessionType.PLAY, device, channel, STREAM);

        verify(ssrcFactory).releaseSsrc("polaris", SSRC);
        verify(sessionManager).removeByStream(anyString(), eq(STREAM));
    }

    @Test
    void stopSkipsRelease_whenSsrcReownedByLiveSession() {
        SsrcTransaction other = new SsrcTransaction();
        other.setApp("rtp");
        other.setStream("other-stream");
        other.setSsrc(SSRC);
        other.setAllocatedSsrc(SSRC);
        other.setMediaServerId("polaris");
        when(sessionManager.getAll()).thenReturn(List.of(other));

        service.stop(InviteSessionType.PLAY, device, channel, STREAM);

        verify(ssrcFactory, never()).releaseSsrc(anyString(), anyString());
    }
}
