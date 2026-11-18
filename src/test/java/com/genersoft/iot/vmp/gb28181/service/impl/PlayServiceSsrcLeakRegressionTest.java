package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionStatus;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.event.SipSubscribe;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.gb28181.session.SSRCFactory;
import com.genersoft.iot.vmp.gb28181.session.SipInviteSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import com.genersoft.iot.vmp.service.ISendRtpServerService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sip.SipException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SSRC 泄漏回归(2026-11-18 审查第 1~6 条):
 * ①~② 回放/下载 INVITE 被拒/发送异常/收流超时;③ 点播 INVITE 同步发送异常;
 * ④ stop BYE 失败;⑤ 流注销兜底 BYE;⑥ 热路径清理旧 invite。
 * 核心断言:releaseAllocatedSsrc 内部调 mediaServerService.releaseSsrc — 验证该调用发生。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class PlayServiceSsrcLeakRegressionTest {

    private static final String DEVICE_ID = "34020000001320000001";
    private static final String CHANNEL_GB_ID = "34020000001320000002";
    private static final Integer CHANNEL_ID = 1;
    private static final String STREAM = DEVICE_ID + "_" + CHANNEL_GB_ID;
    private static final String SSRC = "1200000001";
    private static final String MEDIA_ID = "media-1";
    private static final String START = "2026-11-18 00:00:00";
    private static final String END = "2026-11-18 00:10:00";

    private PlayServiceImpl service;
    private IInviteStreamService inviteStreamService;
    private SipInviteSessionManager sessionManager;
    private ISIPCommander cmder;
    private IReceiveRtpServerService receiveRtpServerService;
    private IMediaServerService mediaServerService;
    private SSRCFactory ssrcFactory;
    private IDeviceService deviceService;
    private MediaServer mediaServer;
    private Device device;
    private DeviceChannel channel;
    private SSRCInfo ssrcInfo;

    @BeforeEach
    void setUp() {
        service = new PlayServiceImpl();
        inviteStreamService = mock(IInviteStreamService.class);
        sessionManager = mock(SipInviteSessionManager.class);
        cmder = mock(ISIPCommander.class);
        receiveRtpServerService = mock(IReceiveRtpServerService.class);
        mediaServerService = mock(IMediaServerService.class);
        ssrcFactory = mock(SSRCFactory.class);
        deviceService = mock(IDeviceService.class);

        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");
        ReflectionTestUtils.setField(userSetting, "playTimeout", 30000);
        ReflectionTestUtils.setField(userSetting, "recordSip", false);

        ReflectionTestUtils.setField(service, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "cmder", cmder);
        ReflectionTestUtils.setField(service, "receiveRtpServerService", receiveRtpServerService);
        ReflectionTestUtils.setField(service, "mediaServerService", mediaServerService);
        ReflectionTestUtils.setField(service, "ssrcFactory", ssrcFactory);
        ReflectionTestUtils.setField(service, "deviceService", deviceService);
        ReflectionTestUtils.setField(service, "deviceChannelService", mock(IDeviceChannelService.class));
        ReflectionTestUtils.setField(service, "sendRtpServerService", mock(ISendRtpServerService.class));
        ReflectionTestUtils.setField(service, "userSetting", userSetting);

        mediaServer = mock(MediaServer.class);
        when(mediaServer.getId()).thenReturn(MEDIA_ID);
        when(mediaServer.getSdpIp()).thenReturn("172.24.0.3");
        device = mock(Device.class);
        when(device.getServerId()).thenReturn("000000");
        when(device.getDeviceId()).thenReturn(DEVICE_ID);
        when(device.getStreamMode()).thenReturn("UDP");
        channel = mock(DeviceChannel.class);
        when(channel.getId()).thenReturn(CHANNEL_ID);
        when(channel.getDeviceId()).thenReturn(CHANNEL_GB_ID);

        ssrcInfo = new SSRCInfo(10003, SSRC, MediaStreamUtil.RTP_APP, STREAM);
        ssrcInfo.setAllocatedSsrc(SSRC);
        when(receiveRtpServerService.getPlaybackStream(any(), any(), any(), any())).thenReturn(STREAM);
    }

    private void invokePlayback() {
        ErrorCallback<StreamInfo> cb = (c, m, d) -> { };
        try {
            ReflectionTestUtils.invokeMethod(service, "playBack", mediaServer, device, channel, START, END, cb);
        } catch (Exception ignored) {
        }
    }

    private void invokeDownload() {
        ErrorCallback<StreamInfo> cb = (c, m, d) -> { };
        try {
            ReflectionTestUtils.invokeMethod(service, "download", mediaServer, device, channel, START, END, 1, cb);
        } catch (Exception ignored) {
        }
    }

    // ---------- ① 回放/下载 INVITE 被拒(通过 ArgumentCaptor 触发错误事件) ----------

    @Test
    void playbackInviteRejectedReleasesSsrc() throws Exception {
        when(receiveRtpServerService.openGbRTPServerForPlayback(any(), any(), any(), any(), any(), any())).thenReturn(ssrcInfo);
        invokePlayback();

        // 捕获 playbackStreamCmd 的 errorEvent(第 8 个参数,index 7)并手动触发
        ArgumentCaptor<SipSubscribe.Event> errCap = ArgumentCaptor.forClass(SipSubscribe.Event.class);
        verify(cmder).playbackStreamCmd(any(), any(), any(), any(), any(), any(), any(), errCap.capture(), any());
        errCap.getValue().response(new SipSubscribe.EventResult());

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    @Test
    void downloadInviteRejectedReleasesSsrc() throws Exception {
        when(receiveRtpServerService.openGbRTPServerForDownload(any(), any(), any(), any(), any(), any())).thenReturn(ssrcInfo);
        invokeDownload();

        ArgumentCaptor<SipSubscribe.Event> errCap = ArgumentCaptor.forClass(SipSubscribe.Event.class);
        verify(cmder).downloadStreamCmd(any(), any(), any(), any(), any(), any(), anyInt(), errCap.capture(), any(), any());
        errCap.getValue().response(new SipSubscribe.EventResult());

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    // ---------- ①b 回放/下载 INVITE 同步发送异常(通过 doThrow) ----------

    @Test
    void playbackSendExceptionReleasesSsrc() throws Exception {
        when(receiveRtpServerService.openGbRTPServerForPlayback(any(), any(), any(), any(), any(), any())).thenReturn(ssrcInfo);
        doThrow(new SipException("send fail"))
                .when(cmder).playbackStreamCmd(any(), any(), any(), any(), any(), any(), any(), any(), any());

        invokePlayback();

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    @Test
    void downloadSendExceptionReleasesSsrc() throws Exception {
        when(receiveRtpServerService.openGbRTPServerForDownload(any(), any(), any(), any(), any(), any())).thenReturn(ssrcInfo);
        doThrow(new SipException("send fail"))
                .when(cmder).downloadStreamCmd(any(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any());

        invokeDownload();

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    // ---------- ② 回放/下载收流超时(通过 ArgumentCaptor 触发超时回调) ----------

    @Test
    void playbackStreamTimeoutReleasesSsrcOnce() {
        ArgumentCaptor<ErrorCallback> cap = ArgumentCaptor.forClass(ErrorCallback.class);
        when(receiveRtpServerService.openGbRTPServerForPlayback(any(), any(), any(), any(), any(), cap.capture())).thenReturn(ssrcInfo);

        invokePlayback();
        cap.getValue().run(InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), "timeout", null);
        cap.getValue().run(InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), "timeout", null);

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    @Test
    void downloadStreamTimeoutReleasesSsrcOnce() {
        ArgumentCaptor<ErrorCallback> cap = ArgumentCaptor.forClass(ErrorCallback.class);
        when(receiveRtpServerService.openGbRTPServerForDownload(any(), any(), any(), any(), any(), cap.capture())).thenReturn(ssrcInfo);

        invokeDownload();
        cap.getValue().run(InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), "timeout", null);
        cap.getValue().run(InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), "timeout", null);

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    // ---------- ③ 点播 INVITE 同步发送异常 ----------

    @Test
    void playSendExceptionReleasesSsrc() throws Exception {
        when(receiveRtpServerService.openGbRTPServerForPlay(any(), any(), any(), isNull(), anyBoolean(), any())).thenReturn(ssrcInfo);
        doThrow(new SipException("send fail"))
                .when(cmder).playStreamCmd(any(), any(), any(), any(), any(), any(), any());

        ErrorCallback<StreamInfo> cb = (c, m, d) -> { };
        try {
            ReflectionTestUtils.invokeMethod(service, "play", mediaServer, device, channel, null, Boolean.FALSE, cb);
        } catch (Exception ignored) {
        }

        verify(mediaServerService, times(1)).releaseSsrc(MEDIA_ID, SSRC);
    }

    // ---------- ④ stop BYE 失败 ----------

    @Test
    void stopStillReleasesSsrcWhenByeFails() throws Exception {
        InviteInfo inviteInfo = new InviteInfo();
        inviteInfo.setType(InviteSessionType.PLAY);
        inviteInfo.setStatus(InviteSessionStatus.ok);
        inviteInfo.setStream(STREAM);
        inviteInfo.setChannelId(CHANNEL_ID);
        inviteInfo.setSsrcInfo(ssrcInfo);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setMediaServer(mediaServer);
        inviteInfo.setStreamInfo(streamInfo);
        when(inviteStreamService.getInviteInfo(InviteSessionType.PLAY, CHANNEL_ID, STREAM)).thenReturn(inviteInfo);
        doThrow(new SipException("send fail"))
                .when(cmder).streamByeCmd(any(Device.class), anyString(), anyString(), anyString(), isNull(), isNull());

        assertThrows(ControllerException.class, () -> service.stop(InviteSessionType.PLAY, device, channel, STREAM));

        verify(ssrcFactory).releaseSsrc(MEDIA_ID, SSRC);
        verify(receiveRtpServerService).closeRTPServer(mediaServer, MediaStreamUtil.RTP_APP, STREAM);
    }

    // ---------- ⑤ 流注销兜底 BYE ----------

    private SsrcTransaction txn(String callId) {
        SsrcTransaction txn = new SsrcTransaction();
        txn.setCallId(callId);
        txn.setDeviceId(DEVICE_ID);
        txn.setApp(MediaStreamUtil.RTP_APP);
        txn.setStream(STREAM);
        txn.setSsrc(SSRC);
        txn.setAllocatedSsrc(SSRC);
        txn.setMediaServerId(MEDIA_ID);
        return txn;
    }

    private MediaDepartureEvent departure() {
        MediaDepartureEvent event = new MediaDepartureEvent(this);
        event.setApp(MediaStreamUtil.RTP_APP);
        event.setStream(STREAM);
        return event;
    }

    @Test
    void departureFallbackByeReleasesSsrcBeforeBye() {
        SsrcTransaction fallback = txn("fallback-call");
        when(inviteStreamService.getInviteInfoByStream(any(), eq(STREAM))).thenReturn(null);
        when(sessionManager.getSsrcTransactionByStream(MediaStreamUtil.RTP_APP, STREAM)).thenReturn(fallback);
        when(sessionManager.getAll()).thenReturn(List.of(fallback));
        when(deviceService.getDeviceByDeviceId(DEVICE_ID)).thenReturn(new Device());

        service.onApplicationEvent(departure());

        verify(ssrcFactory).releaseSsrc(MEDIA_ID, SSRC);
        verify(sessionManager).removeByCallId("fallback-call");
    }

    @Test
    void departureFallbackByeSkipsReleaseWhenSsrcReowned() {
        SsrcTransaction fallback = txn("fallback-call");
        SsrcTransaction owner = txn("live-call");
        when(inviteStreamService.getInviteInfoByStream(any(), eq(STREAM))).thenReturn(null);
        when(sessionManager.getSsrcTransactionByStream(MediaStreamUtil.RTP_APP, STREAM)).thenReturn(fallback);
        when(sessionManager.getAll()).thenReturn(List.of(fallback, owner));
        when(deviceService.getDeviceByDeviceId(DEVICE_ID)).thenReturn(new Device());

        service.onApplicationEvent(departure());

        verify(ssrcFactory, never()).releaseSsrc(anyString(), anyString());
        verify(sessionManager).removeByCallId("fallback-call");
    }

    // ---------- ⑥ 热路径清理旧 invite 时同步还旧号 ----------

    @Test
    void staleInviteCleanupReleasesOldSsrc() {
        InviteInfo stale = new InviteInfo();
        stale.setType(InviteSessionType.PLAY);
        stale.setStatus(InviteSessionStatus.ready);
        stale.setStream(STREAM);
        stale.setChannelId(CHANNEL_ID);
        stale.setSsrcInfo(ssrcInfo);
        StreamInfo staleStream = new StreamInfo();
        staleStream.setStream(STREAM);
        staleStream.setMediaServer(mediaServer);
        stale.setStreamInfo(staleStream);

        when(inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, CHANNEL_ID)).thenReturn(stale);
        when(mediaServerService.isStreamReady(any(MediaServer.class), eq(MediaStreamUtil.RTP_APP), eq(STREAM))).thenReturn(false);

        ErrorCallback<StreamInfo> cb = (c, m, d) -> { };
        try {
            ReflectionTestUtils.invokeMethod(service, "play", mediaServer, device, channel, null, Boolean.FALSE, cb);
        } catch (Exception ignored) {
        }

        verify(mediaServerService).releaseSsrc(MEDIA_ID, SSRC);
        verify(inviteStreamService).removeInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, CHANNEL_ID);
    }

    // ---------- helper ----------

    private SSRCInfo receiveRTPServerForDownload() {
        return ssrcInfo;
    }
}
