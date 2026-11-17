package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * departure 兜底 BYE 行为锁定(2026-11-17 幽灵流第一波修复④):
 * 幽灵根因=竞速漏发 BYE(某路径抢先删 invite→停播链读到 null→静默跳过)。
 * 修法:停播链读到 invite 为空时,不静默跳过——查 SsrcTransaction 会话记录,
 * 有则用该记录的 Call-ID 补发 BYE(兜底)。会话记录也空时才真正跳过。
 */
class PlayServiceDepartureFallbackByeTest {

    private PlayServiceImpl service;
    private IInviteStreamService inviteStreamService;
    private SipInviteSessionManager sessionManager;
    private ISIPCommander cmder;
    private IDeviceService deviceService;

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        inviteStreamService = mock(IInviteStreamService.class);
        sessionManager = mock(SipInviteSessionManager.class);
        cmder = mock(ISIPCommander.class);
        deviceService = mock(IDeviceService.class);
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");

        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "cmder", cmder);
        ReflectionTestUtils.setField(service, "deviceService", deviceService);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);
        ReflectionTestUtils.setField(service, "ssrcFactory", mock(SSRCFactory.class));
        ReflectionTestUtils.setField(service, "deviceChannelService", mock(IDeviceChannelService.class));
        ReflectionTestUtils.setField(service, "receiveRtpServerService", mock(IReceiveRtpServerService.class));
        ReflectionTestUtils.setField(service, "sendRtpServerService", mock(ISendRtpServerService.class));
    }

    private MediaDepartureEvent buildEvent(String app, String stream) {
        MediaDepartureEvent event = new MediaDepartureEvent(this);
        event.setApp(app);
        event.setStream(stream);
        return event;
    }

    @Test
    void inviteDeletedButSessionExistsSendsFallbackBye() throws Exception {
        String stream = "34020000001320000001_34020000001320000002";
        // invite 已被竞速删除
        when(inviteStreamService.getInviteInfoByStream(any(), eq(stream))).thenReturn(null);
        // 但会话记录还在
        SsrcTransaction txn = new SsrcTransaction();
        txn.setCallId("fallback-call-id");
        txn.setDeviceId("34020000001320000001");
        txn.setApp("rtp");
        txn.setStream(stream);
        when(sessionManager.getSsrcTransactionByStream("rtp", stream)).thenReturn(txn);
        Device device = new Device();
        when(deviceService.getDeviceByDeviceId("34020000001320000001")).thenReturn(device);

        service.onApplicationEvent(buildEvent("rtp", stream));

        verify(cmder, atLeastOnce()).streamByeCmd(
                eq(device), anyString(), eq("rtp"), eq(stream), eq("fallback-call-id"), any());
    }

    @Test
    void bothInviteAndSessionNullSkipsBye() throws Exception {
        String stream = "34020000001320000001_34020000001320000002";
        when(inviteStreamService.getInviteInfoByStream(any(), eq(stream))).thenReturn(null);
        when(sessionManager.getSsrcTransactionByStream("rtp", stream)).thenReturn(null);

        service.onApplicationEvent(buildEvent("rtp", stream));

        verify(cmder, never()).streamByeCmd(any(), anyString(), anyString(), anyString(), anyString(), any());
    }
}
