package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.service.bean.SSRCInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 单端口 SSRC 修正后的缓存同步(2026-11-18 审查第 8 条):
 * 设备 200OK 返回的 SSRC 与分配值不一致时,调用方已还号并清空内存中的 allocatedSsrc,
 * 但 Redis 中 invite 仍留旧 allocatedSsrc,stop 时会按旧号再还一次——此时该号可能已分给别的流。
 */
@SuppressWarnings("unchecked")
class InviteStreamServiceSsrcCorrectionTest {

    private static final String STREAM = "34020000001320000001_34020000001320000002";
    private static final String ALLOCATED = "0200000536";
    private static final String DEVICE_SSRC = "0123456789";

    @Test
    void ssrcCorrectionClearsCachedAllocatedSsrcWithoutDeleting() {
        InviteStreamServiceImpl service = new InviteStreamServiceImpl();
        RedisTemplate<Object, Object> redisTemplate = mock(RedisTemplate.class);
        HashOperations<Object, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId("000000");
        ReflectionTestUtils.setField(service, "userSetting", userSetting);

        InviteInfo cached = new InviteInfo();
        cached.setType(InviteSessionType.PLAY);
        cached.setChannelId(1);
        cached.setStream(STREAM);
        SSRCInfo cachedSsrc = new SSRCInfo(10003, ALLOCATED, "rtp", STREAM);
        cachedSsrc.setAllocatedSsrc(ALLOCATED);
        cached.setSsrcInfo(cachedSsrc);

        Cursor<Map.Entry<Object, Object>> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenReturn(Map.entry("PLAY:1:" + STREAM, cached));
        when(hashOps.scan(any(), any())).thenReturn(cursor);

        // 调用方内存态:已还号,allocatedSsrc 已清空
        InviteInfo caller = new InviteInfo();
        caller.setType(InviteSessionType.PLAY);
        caller.setChannelId(1);
        caller.setStream(STREAM);
        SSRCInfo callerSsrc = new SSRCInfo(10003, ALLOCATED, "rtp", STREAM);
        caller.setSsrcInfo(callerSsrc);

        service.updateInviteInfoForSSRC(caller, DEVICE_SSRC);

        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(hashOps).put(any(), eq("PLAY:1:" + STREAM), written.capture());
        InviteInfo saved = (InviteInfo) written.getValue();
        assertEquals(DEVICE_SSRC, saved.getSsrcInfo().getSsrc());
        assertNull(saved.getSsrcInfo().getSsrcToRelease());
        verify(hashOps, never()).delete(any(), any());
    }
}
