package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * §5 最小修的行为锁定(见 docs/WVP-GB28181-Pro资料/WVP异步事件管线停摆诊断-getSnap风暴_20261102.md):
 * snapOnPlay 不得在调用线程上同步等待 ZLM 截图——否则 100 路同秒点播时共享 @Async 8 线程池
 * 被整队占满 14~26s,事件通知队尾越过 30s 死线即假失败。
 */
class PlayServiceSnapAsyncTest {

    private PlayServiceImpl service;
    private IMediaServerService mediaServerService;
    private IInviteStreamService inviteStreamService;

    private static final String STREAM_ID = "test-stream";

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        mediaServerService = mock(IMediaServerService.class);
        inviteStreamService = mock(IInviteStreamService.class);
        com.genersoft.iot.vmp.conf.UserSetting userSetting = new com.genersoft.iot.vmp.conf.UserSetting();
        userSetting.setSnapOnPlay(true);
        ReflectionTestUtils.setField(service, "mediaServerService", mediaServerService);
        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "userSetting", userSetting);
    }

    private void stubStreamAlive() {
        InviteInfo inviteInfo = new InviteInfo();
        inviteInfo.setStreamInfo(new StreamInfo());
        when(inviteStreamService.getInviteInfoByStream(InviteSessionType.PLAY, STREAM_ID)).thenReturn(inviteInfo);
    }

    @Test
    void snapOnPlay_returnsImmediately_whileGetSnapBlocks() throws Exception {
        stubStreamAlive();
        CountDownLatch release = new CountDownLatch(1);
        when(mediaServerService.getSnap(any(MediaServer.class), anyString(), eq(STREAM_ID),
                anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    // 模拟 ZLM 出图慢:占住执行线程直到测试放行(或 2s 兜底)
                    release.await(2, TimeUnit.SECONDS);
                    return null;
                });

        long start = System.currentTimeMillis();
        ReflectionTestUtils.invokeMethod(service, "snapOnPlay",
                new MediaServer(), "34020000001320000001", "34020000001320000002", STREAM_ID);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1000,
                "snapOnPlay 应立即返回(截图异步化),实际耗时 " + elapsed + "ms——回调线程仍被 getSnap 同步阻塞");
        release.countDown();
    }

    @Test
    void getSnap_runsOnDedicatedSnapThread_with3sTimeout() throws Exception {
        stubStreamAlive();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        AtomicInteger timeoutArg = new AtomicInteger(-1);
        when(mediaServerService.getSnap(any(MediaServer.class), anyString(), eq(STREAM_ID),
                anyInt(), anyInt(), any(), any()))
                .thenAnswer(inv -> {
                    timeoutArg.set(inv.getArgument(3));
                    workerThread.set(Thread.currentThread().getName());
                    entered.countDown();
                    return null;
                });

        ReflectionTestUtils.invokeMethod(service, "snapOnPlay",
                new MediaServer(), "34020000001320000001", "34020000001320000002", STREAM_ID);

        assertTrue(entered.await(5, TimeUnit.SECONDS), "getSnap 应被调用");
        assertTrue(workerThread.get().startsWith("snap-"),
                "getSnap 应在专用 snap 线程执行,实际线程: " + workerThread.get());
        assertEquals(3, timeoutArg.get(), "SNAP_TIMEOUT_SEC 应为 3s(§5 最小修)");
    }
}
