package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * invite 超龄收割器行为锁定(2026-11-16,S8 两案定谳):
 * ①11 号未归=终局竞速窗(迟至流垂死×PlayStop×台账 DELETE)删会话未还号;
 * ②invite hash 1943 条跨战役累积(S4 §16 旧案)。
 * 两者同根(stream 键生命周期重叠)不同账本,收割器统一对平:
 * 超龄(createTime 为 null 的存量记录视为陈旧)+流死 → 删会话记录+还号;
 * 在册同号护栏:号码若被任一保留记录持有,只清记录不还号,防偷号(S4 前科)。
 */
class SipInviteSessionReaperTest {

    private SipInviteSessionReaper reaper;
    private SipInviteSessionManager sessionManager;
    private IMediaServerService mediaServerService;
    private SSRCFactory ssrcFactory;

    private SsrcTransaction build(String callId, String ssrc, Long createTime) {
        SsrcTransaction t = new SsrcTransaction();
        t.setCallId(callId);
        t.setApp("rtp");
        t.setStream("34020000001320000001_34020000001320000002");
        t.setMediaServerId("media-1");
        t.setSsrc(ssrc);
        t.setAllocatedSsrc(ssrc);
        t.setType(InviteSessionType.PLAY);
        t.setCreateTime(createTime);
        return t;
    }

    @BeforeEach
    void setUp() {
        reaper = new SipInviteSessionReaper();
        sessionManager = mock(SipInviteSessionManager.class);
        mediaServerService = mock(IMediaServerService.class);
        ssrcFactory = mock(SSRCFactory.class);
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");

        ReflectionTestUtils.setField(reaper, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(reaper, "mediaServerService", mediaServerService);
        ReflectionTestUtils.setField(reaper, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(reaper, "userSetting", userSetting);

        MediaServer ms = new MediaServer();
        when(mediaServerService.getOne("media-1")).thenReturn(ms);
        when(mediaServerService.isStreamReady(any(), eq("rtp"), anyString())).thenReturn(false);
    }

    @Test
    void staleAndDeadStreamIsReaped() {
        when(sessionManager.getAll()).thenReturn(List.of(build("c1", "0100000001", null)));
        int n = reaper.sweepOnce();
        org.junit.jupiter.api.Assertions.assertEquals(1, n);
        verify(sessionManager).removeByCallId("c1");
        verify(ssrcFactory).releaseSsrc("media-1", "0100000001");
    }

    @Test
    void youngRecordIsKept() {
        when(sessionManager.getAll()).thenReturn(List.of(build("c2", "0100000002", System.currentTimeMillis())));
        reaper.sweepOnce();
        verify(sessionManager, never()).removeByCallId(anyString());
        verify(ssrcFactory, never()).releaseSsrc(anyString(), anyString());
    }

    @Test
    void aliveStreamIsKept() {
        when(sessionManager.getAll()).thenReturn(List.of(build("c3", "0100000003", 1L)));
        when(mediaServerService.isStreamReady(any(), eq("rtp"), anyString())).thenReturn(true);
        reaper.sweepOnce();
        verify(sessionManager, never()).removeByCallId(anyString());
    }

    @Test
    void sameSsrcHeldByKeptRecordOnlyRemovesRecordNotRelease() {
        SsrcTransaction stale = build("c4", "0100000004", 1L);
        SsrcTransaction youngHolder = build("c5", "0100000004", System.currentTimeMillis());
        when(sessionManager.getAll()).thenReturn(Arrays.asList(stale, youngHolder));
        reaper.sweepOnce();
        verify(sessionManager).removeByCallId("c4");
        verify(ssrcFactory, never()).releaseSsrc(anyString(), anyString()); // 同号在册,防偷号
    }

    @Test
    void unknownMediaServerOrProbeFailureIsKept() {
        when(mediaServerService.getOne("media-1")).thenReturn(null);
        when(sessionManager.getAll()).thenReturn(List.of(build("c6", "0100000006", null)));
        reaper.sweepOnce();
        verify(sessionManager, never()).removeByCallId(anyString());
        verify(ssrcFactory, never()).releaseSsrc(anyString(), anyString());
    }
}
