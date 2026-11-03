package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * user-settings.snap-on-play 开关行为锁定(2026-11-03):
 * 关闭时点播成功回调不得触发任何截图(连任务都不提交);
 * 只闸 snapOnPlay 自动封面,不影响按需截图 API(由 PlayController 测试域覆盖)。
 */
class PlayServiceSnapSwitchTest {

    private PlayServiceImpl service;
    private IMediaServerService mediaServerService;
    private IInviteStreamService inviteStreamService;
    private UserSetting userSetting;

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        mediaServerService = mock(IMediaServerService.class);
        inviteStreamService = mock(IInviteStreamService.class);
        userSetting = new UserSetting();
        userSetting.setServerId("000000");

        ReflectionTestUtils.setField(service, "mediaServerService", mediaServerService);
        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);

        InviteInfo inviteInfo = new InviteInfo();
        inviteInfo.setStreamInfo(new StreamInfo());
        when(inviteStreamService.getInviteInfoByStream(any(), anyString())).thenReturn(inviteInfo);
    }

    @Test
    void snapOnPlay_disabled_neverTriggersGetSnap() throws Exception {
        userSetting.setSnapOnPlay(false);
        CountDownLatch snapEntered = new CountDownLatch(1);
        AtomicBoolean invoked = new AtomicBoolean(false);
        when(mediaServerService.getSnap(any(MediaServer.class), anyString(), anyString(),
                anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    invoked.set(true);
                    snapEntered.countDown();
                    return null;
                });

        ReflectionTestUtils.invokeMethod(service, "snapOnPlay",
                new MediaServer(), "34020000001320000001", "34020000001320000002", "test-stream");

        // 给异步池足够窗口证明"没有提交任何任务",而非"还没来得及执行"
        boolean firedDuringWindow = snapEntered.await(2, TimeUnit.SECONDS);
        assertFalse(firedDuringWindow, "开关关闭时不应触发截图,实际 getSnap 被调用=" + invoked.get());
        verify(mediaServerService, never()).getSnap(any(), anyString(), anyString(),
                anyInt(), anyInt(), any(), any());
    }

    @Test
    void snapOnPlay_defaultIsOff() {
        // 2026-11-03 用户定夺:封面纯界面点缀且有 11-02 停摆事故史,默认关闭改为显式开启(opt-in)
        assertFalse(new UserSetting().isSnapOnPlay(), "snapOnPlay 默认必须为 false(opt-in)");
    }

    @Test
    void snapOnPlay_enabled_submitsSnapTask() throws Exception {
        userSetting.setSnapOnPlay(true); // 显式开启
        CountDownLatch snapEntered = new CountDownLatch(1);
        when(mediaServerService.getSnap(any(MediaServer.class), anyString(), anyString(),
                anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    snapEntered.countDown();
                    return null;
                });

        ReflectionTestUtils.invokeMethod(service, "snapOnPlay",
                new MediaServer(), "34020000001320000001", "34020000001320000002", "test-stream");

        assertTrue(snapEntered.await(5, TimeUnit.SECONDS), "开关开启时应照常提交截图任务");
    }
}
