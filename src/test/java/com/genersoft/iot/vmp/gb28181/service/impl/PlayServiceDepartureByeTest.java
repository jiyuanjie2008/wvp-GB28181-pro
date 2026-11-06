package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-11-03 晚 P0 修复(重注册循环根治,通俗说明文档 §12.5 修法 A)行为锁定:
 * 1) MediaDepartureEvent 的 GB28181 会话清理唯一 owner = PlayServiceImpl(InviteStreamServiceImpl 冗余监听器已删);
 * 2) GB28181 分支位于方法最前、不被 sendRtp 查询阻塞——流注销即挂电话(发 BYE+清会话+还号);
 * 3) 竞速曾致 BYE 永不发出:InviteStreamServiceImpl 抢先删 invite,PlayServiceImpl 读 null 静默跳过。
 */
class PlayServiceDepartureByeTest {

    private static final String STREAM = "34020000001320000001_34020000001320000002";

    private PlayServiceImpl service;
    private IInviteStreamService inviteStreamService;
    private ISIPCommander cmder;
    private SSRCFactory ssrcFactory;
    private IDeviceChannelService deviceChannelService;
    private IDeviceService deviceService;
    private ISendRtpServerService sendRtpServerService;
    private IReceiveRtpServerService receiveRtpServerService;

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        inviteStreamService = mock(IInviteStreamService.class);
        cmder = mock(ISIPCommander.class);
        ssrcFactory = mock(SSRCFactory.class);
        deviceChannelService = mock(IDeviceChannelService.class);
        deviceService = mock(IDeviceService.class);
        sendRtpServerService = mock(ISendRtpServerService.class);
        receiveRtpServerService = mock(IReceiveRtpServerService.class);

        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "cmder", cmder);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(service, "deviceService", deviceService);
        ReflectionTestUtils.setField(service, "sendRtpServerService", sendRtpServerService);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtpServerService);
        ReflectionTestUtils.setField(service, "mediaServerService", mock(IMediaServerService.class));

        when(sendRtpServerService.queryByStream(anyString())).thenReturn(Collections.emptyList());

        DeviceChannel channel = mock(DeviceChannel.class);
        when(channel.getId()).thenReturn(1);
        when(channel.getDeviceId()).thenReturn("34020000001320000002");
        when(channel.getDataDeviceId()).thenReturn(1);
        when(deviceChannelService.getOneForSourceById(any())).thenReturn(channel);
        Device device = mock(Device.class);
        when(device.getDeviceId()).thenReturn("34020000001320000001");
        when(deviceService.getDevice(anyInt())).thenReturn(device);
    }

    private MediaDepartureEvent departureEvent(String app, String stream) {
        MediaDepartureEvent event = new MediaDepartureEvent(this);
        event.setApp(app);
        event.setStream(stream);
        return event;
    }

    private InviteInfo okPlayInvite() {
        InviteInfo inviteInfo = new InviteInfo();
        inviteInfo.setDeviceId("34020000001320000001");
        inviteInfo.setChannelId(1);
        inviteInfo.setType(InviteSessionType.PLAY);
        inviteInfo.setStatus(InviteSessionStatus.ok);
        inviteInfo.setStream(STREAM);
        SSRCInfo ssrcInfo = new SSRCInfo(10003, "0200000536", "rtp", STREAM);
        ssrcInfo.setAllocatedSsrc("0200000536");
        inviteInfo.setSsrcInfo(ssrcInfo);
        StreamInfo streamInfo = new StreamInfo();
        MediaServer mediaServer = new MediaServer();
        mediaServer.setId("polaris");
        streamInfo.setMediaServer(mediaServer);
        inviteInfo.setStreamInfo(streamInfo);
        return inviteInfo;
    }

    @Test
    void departure_gb28181_sendsByeAndTearsDownSession() throws Exception {
        // 核心断言:流注销 → 发 BYE + 清 invite + 还 SSRC(重注册循环的根治点)
        InviteInfo inviteInfo = okPlayInvite();
        when(inviteStreamService.getInviteInfoByStream(any(), eq(STREAM))).thenReturn(inviteInfo);

        service.onApplicationEvent(departureEvent("rtp", STREAM));

        verify(cmder).streamByeCmd(any(), anyString(), anyString(), eq(STREAM), isNull(), isNull());
        verify(inviteStreamService).removeInviteInfo(inviteInfo);
        verify(ssrcFactory).releaseSsrc("polaris", "0200000536");
    }

    @Test
    void departure_inviteMissing_noByeNoException() throws Exception {
        // 竞速输掉/会话已清时不炸、不误发 BYE
        when(inviteStreamService.getInviteInfoByStream(any(), eq(STREAM))).thenReturn(null);

        assertDoesNotThrow(() -> service.onApplicationEvent(departureEvent("rtp", STREAM)));
        verify(cmder, never()).streamByeCmd(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void departure_nonOkStatus_stillTornDownButNoBye() throws Exception {
        // 放宽语义:未成功会话(状态非 ok)也要清理(清 invite+还号),但 BYE 只对 ok 发(stop 内护栏)
        InviteInfo inviteInfo = okPlayInvite();
        inviteInfo.setStatus(InviteSessionStatus.ready);
        when(inviteStreamService.getInviteInfoByStream(any(), eq(STREAM))).thenReturn(inviteInfo);

        service.onApplicationEvent(departureEvent("rtp", STREAM));

        verify(inviteStreamService).removeInviteInfo(inviteInfo);
        verify(ssrcFactory).releaseSsrc("polaris", "0200000536");
        verify(cmder, never()).streamByeCmd(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void departure_talkApp_notRoutedToGbStop() throws Exception {
        // 对讲/广播流(app=talk/broadcast)不得被 GB28181 分支拦截,仍走专属处理链
        service.onApplicationEvent(departureEvent("talk", STREAM));

        verify(inviteStreamService, never()).getInviteInfoByStream(any(), anyString());
        verify(cmder, never()).streamByeCmd(any(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void departure_queriesInviteExactlyOnce() {
        // 防回归:GB28181 分支唯一化后,一次事件只应查一次 invite(尾部旧分支会查第二次)
        InviteInfo inviteInfo = okPlayInvite();
        when(inviteStreamService.getInviteInfoByStream(any(), eq(STREAM))).thenReturn(inviteInfo);

        service.onApplicationEvent(departureEvent("rtp", STREAM));

        verify(inviteStreamService, times(1)).getInviteInfoByStream(any(), eq(STREAM));
    }

    @Test
    void inviteStreamServiceImpl_hasNoDepartureListener() throws NoSuchMethodException {
        // 12.5 修法 A 步骤 1 防回归:冗余监听器不得复活(它曾抢删 invite 致 BYE 永不发出)
        assertThrows(NoSuchMethodException.class, () ->
                InviteStreamServiceImpl.class.getDeclaredMethod("onApplicationEvent", MediaDepartureEvent.class));
    }

    @Test
    void departureListener_declaresOrder() throws NoSuchMethodException {
        // 12.5 修法 A 步骤 3 防回归:唯一 owner 监听器须显式 @Order(声明调度优先级,防止未来监听器乱序抢跑)
        Method listener = PlayServiceImpl.class.getDeclaredMethod("onApplicationEvent", MediaDepartureEvent.class);
        assertNotNull(listener.getAnnotation(Order.class), "MediaDepartureEvent 监听器应显式声明 @Order");
    }
}
