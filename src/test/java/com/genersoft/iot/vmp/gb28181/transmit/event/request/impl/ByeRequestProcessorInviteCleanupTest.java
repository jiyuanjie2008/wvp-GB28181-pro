package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.gb28181.bean.DeviceChannel;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceChannelService;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.service.IReceiveRtpServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 设备主动 BYE 的 invite 清理(2026-11-18 审查第 7 条):
 * 此前固定按 PLAY 类型查 invite,回放/下载会话的 invite 残留(还可能误删同通道实时点播的 invite),
 * 之后 stop 读到残留 invite 会按旧号再还一次——该号可能已分给别的流。
 */
class ByeRequestProcessorInviteCleanupTest {

    private static final String STREAM = "34020000001320000001_34020000001320000002_playback";

    private ByeRequestProcessor processor;
    private IInviteStreamService inviteStreamService;
    private IDeviceChannelService deviceChannelService;
    private IReceiveRtpServerService receiveRtpServerService;
    private DeviceChannel channel;
    private MediaServer mediaServer;

    @BeforeEach
    void setUp() {
        processor = new ByeRequestProcessor();
        inviteStreamService = mock(IInviteStreamService.class);
        deviceChannelService = mock(IDeviceChannelService.class);
        receiveRtpServerService = mock(IReceiveRtpServerService.class);
        ReflectionTestUtils.setField(processor, "inviteStreamService", inviteStreamService);
        ReflectionTestUtils.setField(processor, "deviceChannelService", deviceChannelService);
        ReflectionTestUtils.setField(processor, "receiveRtpServerService", receiveRtpServerService);
        channel = mock(DeviceChannel.class);
        when(channel.getId()).thenReturn(1);
        mediaServer = mock(MediaServer.class);
    }

    private SsrcTransaction txn(InviteSessionType type) {
        SsrcTransaction txn = new SsrcTransaction();
        txn.setType(type);
        txn.setApp("rtp");
        txn.setStream(STREAM);
        return txn;
    }

    private InviteInfo invite(InviteSessionType type) {
        InviteInfo inviteInfo = new InviteInfo();
        inviteInfo.setType(type);
        inviteInfo.setChannelId(1);
        inviteInfo.setStream(STREAM);
        StreamInfo streamInfo = new StreamInfo();
        streamInfo.setMediaServer(mediaServer);
        streamInfo.setApp("rtp");
        streamInfo.setStream(STREAM);
        inviteInfo.setStreamInfo(streamInfo);
        return inviteInfo;
    }

    @Test
    void playbackByeRemovesPlaybackInviteByTypeAndStream() {
        InviteInfo playback = invite(InviteSessionType.PLAYBACK);
        when(inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAYBACK, 1)).thenReturn(playback);

        processor.cleanInviteForDeviceBye(txn(InviteSessionType.PLAYBACK), channel);

        verify(inviteStreamService).removeInviteInfo(playback);
        verify(receiveRtpServerService).closeRTPServer(mediaServer, "rtp", STREAM);
        // 核心断言:查的是 PLAYBACK 类型(修复前固定查 PLAY 导致回放 invite 残留)
        verify(inviteStreamService, never()).getInviteInfoByDeviceAndChannel(eq(InviteSessionType.PLAY), any());
    }

    @Test
    void playByeStillStopsPlayAndRemovesInvite() {
        InviteInfo play = invite(InviteSessionType.PLAY);
        when(inviteStreamService.getInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, 1)).thenReturn(play);

        processor.cleanInviteForDeviceBye(txn(InviteSessionType.PLAY), channel);

        verify(deviceChannelService).stopPlay(1);
        verify(inviteStreamService).removeInviteInfo(play);
    }
}
