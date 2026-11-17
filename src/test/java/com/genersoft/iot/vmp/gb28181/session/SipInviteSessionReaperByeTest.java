package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 收割器补发 BYE 行为锁定(2026-11-17 幽灵流第一波修复②):
 * 幽灵根因=竞速漏发 BYE→设备持续推流→ZLM 流名被占→通道假死。
 * 修法:收割器在清理超龄会话记录时,若设备在线则补发 BYE——给设备"第二次停推机会"。
 * 设备离线时不发(BYE 无意义);发送失败仅告警不阻断收割。
 */
class SipInviteSessionReaperByeTest {

    private SipInviteSessionReaper reaper;
    private SipInviteSessionManager sessionManager;
    private IMediaServerService mediaServerService;
    private SSRCFactory ssrcFactory;
    private ISIPCommander cmder;
    private IDeviceService deviceService;

    private SsrcTransaction buildStale() {
        SsrcTransaction t = new SsrcTransaction();
        t.setCallId("test-call-id");
        t.setApp("rtp");
        t.setStream("34020000001320000001_34020000001320000002");
        t.setMediaServerId("media-1");
        t.setSsrc("0100000001");
        t.setAllocatedSsrc("0100000001");
        t.setType(InviteSessionType.PLAY);
        t.setDeviceId("34020000001320000001");
        t.setCreateTime(1L); // 远超龄
        return t;
    }

    @BeforeEach
    void setUp() {
        reaper = new SipInviteSessionReaper();
        sessionManager = mock(SipInviteSessionManager.class);
        mediaServerService = mock(IMediaServerService.class);
        ssrcFactory = mock(SSRCFactory.class);
        cmder = mock(ISIPCommander.class);
        deviceService = mock(IDeviceService.class);
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");

        ReflectionTestUtils.setField(reaper, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(reaper, "mediaServerService", mediaServerService);
        ReflectionTestUtils.setField(reaper, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(reaper, "userSetting", userSetting);
        ReflectionTestUtils.setField(reaper, "cmder", cmder);
        ReflectionTestUtils.setField(reaper, "deviceService", deviceService);

        MediaServer ms = new MediaServer();
        when(mediaServerService.getOne("media-1")).thenReturn(ms);
        when(mediaServerService.isStreamReady(any(), anyString(), anyString())).thenReturn(false);
    }

    @Test
    void staleWithOnlineDeviceSendsBye() throws Exception {
        when(sessionManager.getAll()).thenReturn(List.of(buildStale()));
        Device device = new Device();
        device.setOnLine(true);
        when(deviceService.getDeviceByDeviceId("34020000001320000001")).thenReturn(device);
        reaper.sweepOnce();
        verify(cmder, atLeastOnce()).streamByeCmd(
                eq(device), anyString(), eq("rtp"), anyString(), eq("test-call-id"), any());
    }

    @Test
    void staleWithOfflineDeviceSkipsBye() throws Exception {
        when(sessionManager.getAll()).thenReturn(List.of(buildStale()));
        when(deviceService.getDeviceByDeviceId("34020000001320000001")).thenReturn(null);
        reaper.sweepOnce();
        verify(cmder, never()).streamByeCmd(any(), anyString(), anyString(), anyString(), anyString(), any());
        // 收割本身不受影响
        verify(sessionManager).removeByCallId("test-call-id");
    }
}
