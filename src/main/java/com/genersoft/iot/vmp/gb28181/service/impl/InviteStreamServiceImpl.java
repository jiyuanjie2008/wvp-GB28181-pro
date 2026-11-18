package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
public class InviteStreamServiceImpl implements IInviteStreamService {

    private final Map<String, List<ErrorCallback<StreamInfo>>> inviteErrorCallbackMap = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Autowired
    private UserSetting userSetting;

    // 20261103 P0 修复(重注册循环根治): 此处原有一个 MediaDepartureEvent 的 @Async 监听器,
    // 职责是"流注销时删除 invite 记录 + 清播放标记"——与 PlayServiceImpl 上同事件的监听器触发条件
    // 完全重叠,且因后者需先查 sendRtpInfo(Redis/DB 往返)才轮到读 invite,本监听器几乎总是抢先
    // 删除 invite,导致 PlayServiceImpl 读到 null 而静默跳过 BYE:摄像头不知情继续推流 → 流被重新
    // 注册 → none_reader 再次关流,形成约 20s 一圈的重注册循环(S4c 实证:流峰 3764、ZLM 3.5GB、
    // 云录像碎成 20s 小段)。spring.threads.virtual.enabled=true 下 @Async 为无界虚拟线程执行器,
    // 两监听器真并行,竞速窗口全开。
    // 本监听器的工作是 PlayServiceImpl.stop(InviteInfo) 的严格子集(后者另含发 BYE、还 SSRC、
    // 关收流端口),故整体删除,由 PlayServiceImpl 单一 owner 承担。
    // 约束: 后续新增 MediaDepartureEvent 监听器一律不得删除 invite 记录。

    @Override
    public void updateInviteInfo(InviteInfo inviteInfo) {
        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            updateInviteInfo(inviteInfo, Long.valueOf(userSetting.getPlayTimeout()) * 2);
        } else {
            updateInviteInfo(inviteInfo, null);
        }
    }

    @Override
    public void updateInviteInfo(InviteInfo inviteInfo, Long time) {
        if (inviteInfo == null || (inviteInfo.getDeviceId() == null || inviteInfo.getChannelId() == null)) {
            log.warn("[更新Invite信息]，参数不全： {}", JSON.toJSON(inviteInfo));
            return;
        }
        InviteInfo inviteInfoForUpdate;

        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            if (inviteInfo.getDeviceId() == null || inviteInfo.getChannelId() == null
                    || inviteInfo.getType() == null || inviteInfo.getStream() == null
            ) {
                return;
            }
            inviteInfoForUpdate = inviteInfo;
        } else {
            InviteInfo inviteInfoInRedis = getInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
            if (inviteInfoInRedis == null) {
                log.warn("[更新Invite信息]，未从缓存中读取到Invite信息： deviceId: {}, channel: {}, stream: {}",
                        inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream());
                return;
            }
            if (inviteInfo.getStreamInfo() != null) {
                inviteInfoInRedis.setStreamInfo(inviteInfo.getStreamInfo());
            }
            if (inviteInfo.getSsrcInfo() != null) {
                inviteInfoInRedis.setSsrcInfo(inviteInfo.getSsrcInfo());
            }
            if (inviteInfo.getStreamMode() != null) {
                inviteInfoInRedis.setStreamMode(inviteInfo.getStreamMode());
            }
            if (inviteInfo.getReceiveIp() != null) {
                inviteInfoInRedis.setReceiveIp(inviteInfo.getReceiveIp());
            }
            if (inviteInfo.getReceivePort() != null) {
                inviteInfoInRedis.setReceivePort(inviteInfo.getReceivePort());
            }
            if (inviteInfo.getStatus() != null) {
                inviteInfoInRedis.setStatus(inviteInfo.getStatus());
            }

            inviteInfoForUpdate = inviteInfoInRedis;

        }
        if (inviteInfoForUpdate.getCreateTime() == null) {
            inviteInfoForUpdate.setCreateTime(System.currentTimeMillis());
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = inviteInfoForUpdate.getType() +
                ":" + inviteInfoForUpdate.getChannelId() +
                ":" + inviteInfoForUpdate.getStream();
        if (time != null && time > 0) {
            inviteInfoForUpdate.setExpirationTime(time);
        }
        redisTemplate.opsForHash().put(key, objectKey, inviteInfoForUpdate);
    }

    @Override
    public InviteInfo updateInviteInfoForStream(InviteInfo inviteInfo, String stream) {

        InviteInfo inviteInfoInDb = getInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
        if (inviteInfoInDb == null) {
            return null;
        }
        removeInviteInfo(inviteInfoInDb);
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = inviteInfo.getType() +
                ":" + inviteInfo.getChannelId() +
                ":" + stream;
        inviteInfoInDb.setStream(stream);
        if (inviteInfoInDb.getSsrcInfo() != null) {
            inviteInfoInDb.getSsrcInfo().setStream(stream);
        }
        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            inviteInfoInDb.setExpirationTime((long) (userSetting.getPlayTimeout() * 2));
        }
        if (inviteInfoInDb.getCreateTime() == null) {
            inviteInfoInDb.setCreateTime(System.currentTimeMillis());
        }
        redisTemplate.opsForHash().put(key, objectKey, inviteInfoInDb);
        return inviteInfoInDb;
    }

    @Override
    public InviteInfo getInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        String keyPattern = (type != null ? type : "*") +
                ":" + (channelId != null ? channelId : "*") +
                ":" + (stream != null ? stream : "*");
        ScanOptions options = ScanOptions.scanOptions().match(keyPattern).count(20).build();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(key, options)) {
            if (cursor.hasNext()) {
                InviteInfo inviteInfo = (InviteInfo) cursor.next().getValue();
                cursor.close();
                return inviteInfo;

            }
        } catch (Exception e) {
            log.error("[Redis-InviteInfo] 查询异常: ", e);
        }
        return null;
    }

    @Override
    public List<InviteInfo> getAllInviteInfo() {
        List<InviteInfo> result = new ArrayList<>();
        String key = VideoManagerConstants.INVITE_PREFIX;
        List<Object> values = redisTemplate.opsForHash().values(key);
        if(values.isEmpty()) {
            return result;
        }
        for (Object value : values) {
            result.add((InviteInfo)value);
        }
        return result;
    }

    @Override
    public InviteInfo getInviteInfoByDeviceAndChannel(InviteSessionType type, Integer channelId) {
        return getInviteInfo(type, channelId, null);
    }

    @Override
    public InviteInfo getInviteInfoByStream(InviteSessionType type, String stream) {
        return getInviteInfo(type, null, stream);
    }

    @Override
    public void removeInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        if (type == null && channelId == null && stream == null) {
            redisTemplate.opsForHash().delete(key);
            return;
        }
        InviteInfo inviteInfo = getInviteInfo(type, channelId, stream);
        if (inviteInfo != null) {
            String objectKey = inviteInfo.getType() +
                    ":" + inviteInfo.getChannelId() +
                    ":" + inviteInfo.getStream();
            redisTemplate.opsForHash().delete(key, objectKey);
        }
    }

    @Override
    public void removeInviteInfoByDeviceAndChannel(InviteSessionType inviteSessionType, Integer channelId) {
        removeInviteInfo(inviteSessionType, channelId, null);
    }

    @Override
    public void removeInviteInfo(InviteInfo inviteInfo) {
        removeInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
    }

    @Override
    public void once(InviteSessionType type, Integer channelId, String stream, ErrorCallback<StreamInfo> callback) {
        String key = buildKey(type, channelId, stream);
        List<ErrorCallback<StreamInfo>> callbacks = inviteErrorCallbackMap.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        callbacks.add(callback);

    }

    private String buildKey(InviteSessionType type, Integer channelId, String stream) {
        String key = type + ":" + channelId;
        // 如果ssrc未null那么可以实现一个通道只能一次操作，ssrc不为null则可以支持一个通道多次invite
        if (stream != null) {
            key += (":" + stream);
        }
        return key;
    }


    @Override
    public void clearInviteInfo(String deviceId) {
        List<InviteInfo> inviteInfoList = getAllInviteInfo();
        for (InviteInfo inviteInfo : inviteInfoList) {
            if (inviteInfo.getDeviceId().equals(deviceId)) {
                removeInviteInfo(inviteInfo);
            }
        }
    }

    @Override
    public int getStreamInfoCount(String mediaServerId) {
        int count = 0;
        String key = VideoManagerConstants.INVITE_PREFIX;
        List<Object> values = redisTemplate.opsForHash().values(key);
        if (values.isEmpty()) {
            return count;
        }
        for (Object value : values) {
            InviteInfo inviteInfo = (InviteInfo)value;
            if (inviteInfo != null
                    && inviteInfo.getStreamInfo() != null
                    && inviteInfo.getStreamInfo().getMediaServer() != null
                    && inviteInfo.getStreamInfo().getMediaServer().getId().equals(mediaServerId)) {
                if (inviteInfo.getType().equals(InviteSessionType.DOWNLOAD) && inviteInfo.getStreamInfo().getProgress() == 1) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    @Override
    public void call(InviteSessionType type, Integer channelId, String stream, int code, String msg, StreamInfo data) {
        String key = buildSubStreamKey(type, channelId, stream);
        List<ErrorCallback<StreamInfo>> callbacks = inviteErrorCallbackMap.get(key);
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        for (ErrorCallback<StreamInfo> callback : callbacks) {
            if (callback != null) {
                callback.run(code, msg, data);
            }
        }
        inviteErrorCallbackMap.remove(key);
    }


    private String buildSubStreamKey(InviteSessionType type, Integer channelId, String stream) {
        String key = type + ":" + channelId;
        if (stream != null) {
            key += (":" + stream);
        }
        return key;
    }

    @Override
    public InviteInfo getInviteInfoBySSRC(String ssrc) {
        List<InviteInfo> inviteInfoList = getAllInviteInfo();
        if (inviteInfoList.isEmpty()) {
            return null;
        }
        for (InviteInfo inviteInfo : inviteInfoList) {
            if (inviteInfo.getSsrcInfo() != null && ssrc.equals(inviteInfo.getSsrcInfo().getSsrc())) {
                return inviteInfo;
            }
        }
        return null;
    }

    @Override
    public InviteInfo updateInviteInfoForSSRC(InviteInfo inviteInfo, String ssrc) {
        InviteInfo inviteInfoInDb = getInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
        if (inviteInfoInDb == null) {
            return null;
        }
        // 同键覆盖写,不先删除:先删后写会留出 invite 短暂缺失的窗口
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = inviteInfo.getType() +
                ":" + inviteInfo.getChannelId() +
                ":" + inviteInfo.getStream();
        if (inviteInfoInDb.getSsrcInfo() != null) {
            inviteInfoInDb.getSsrcInfo().setSsrc(ssrc);
            // 调用方已还号并清空 allocatedSsrc,同步到缓存,防止 stop 时按旧号重复还号
            inviteInfoInDb.getSsrcInfo().setAllocatedSsrc(
                    inviteInfo.getSsrcInfo() != null ? inviteInfo.getSsrcInfo().getAllocatedSsrc() : null);
        }
        redisTemplate.opsForHash().put(key, objectKey, inviteInfoInDb);
        return inviteInfoInDb;
    }

    @Scheduled(fixedRate = 10000)   //定时检测,清理错误的redis数据,防止因为错误数据导致的点播不可用
    public void execute(){
        String key = VideoManagerConstants.INVITE_PREFIX;
        if(redisTemplate.opsForHash().size(key) == 0) {
            return;
        }
        List<Object> values = redisTemplate.opsForHash().values(key);
        for (Object value : values) {
            InviteInfo inviteInfo = (InviteInfo)value;
            if (inviteInfo.getStreamInfo() != null) {
                continue;
            }
            if (inviteInfo.getCreateTime() == null || inviteInfo.getExpirationTime() == 0) {
                removeInviteInfo(inviteInfo);
            }
            long time = inviteInfo.getCreateTime() + inviteInfo.getExpirationTime();
            if (System.currentTimeMillis() > time) {
                removeInviteInfo(inviteInfo);
            }
        }
    }
}
