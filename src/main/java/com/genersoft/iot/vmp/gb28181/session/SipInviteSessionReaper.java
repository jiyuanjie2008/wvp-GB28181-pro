package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.service.IDeviceService;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * invite 会话超龄收割器(2026-11-16,S8 70 轮双案定谳的修复):
 *
 * 病根:会话登记以 stream(通道)为键,而"上一轮垂死迟至流×下一轮同通道重播"生命周期重叠时
 * stream 键被覆盖,清理链按 stream 认错对象——同一裂缝的两个出口:
 * ① SSRC 号不还(0.16 号/轮,终局竞速窗 PlayStop×none_reader×台账 DELETE 三方赛跑);
 * ② 会话 hash 残留(S4 时代 §16 旧案,跨战役累积 1943 条)。
 *
 * 修法:不做分支级精确修补(裂缝遍布清理链多处),改为账本级统一对平——
 * 周期扫描会话表,超龄且流已死的记录:删会话(双索引)+ 还号;
 * 在册同号护栏:号码仍被任一保留记录(年轻/流活)持有时只清记录不还号,防偷号(S4 前科)。
 * 保守性:媒体节点不可解析或活性查询失败 → 本轮跳过,下轮再试。
 *
 * 配置:user-settings.invite-reaper-enabled(默认 true)/ invite-reaper-age-ms(默认 600000)
 *      / invite-reaper-interval-ms(默认 300000,@Scheduled 占位符)。
 */
@Slf4j
@Component
public class SipInviteSessionReaper {

    @Autowired
    private SipInviteSessionManager sessionManager;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private SSRCFactory ssrcFactory;

    @Autowired
    private ISIPCommander cmder;

    @Autowired
    private IDeviceService deviceService;

    @Autowired
    private UserSetting userSetting;

    /**
     * 20261118 收割计数器:收割器每回收一条会话记录即累加,重启归零.
     * 压测断言应为 0(非零=有路径漏还号被兜底,根因需追查);轮差=0 不足以判定无泄漏(收割器可能已悄悄还了).
     * 通过 getTotalReaped() 读取,或 grep "[invite 收割]" WARN 日志计数.
     */
    private static final java.util.concurrent.atomic.AtomicInteger TOTAL_REAPED = new java.util.concurrent.atomic.AtomicInteger(0);

    public static int getTotalReaped() {
        return TOTAL_REAPED.get();
    }

    public static void resetReapCounter() {
        TOTAL_REAPED.set(0);
    }

    @Scheduled(fixedDelayString = "${user-settings.invite-reaper-interval-ms:300000}",
            initialDelayString = "${user-settings.invite-reaper-initial-delay-ms:60000}")
    public void scheduledSweep() {
        try {
            sweepOnce();
        } catch (Exception e) {
            log.warn("[invite 收割] 本轮异常(下轮重试): {}", e.getMessage());
        }
    }

    /**
     * 执行一轮收割,返回收割条数(行为锁定测试入口)。
     */
    public int sweepOnce() {
        if (!userSetting.isInviteReaperEnabled()) {
            return 0;
        }
        List<SsrcTransaction> all = sessionManager.getAll();
        if (all == null || all.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long ageMs = userSetting.getInviteReaperAgeMs();

        List<SsrcTransaction> stale = new ArrayList<>();
        Set<String> keptSsrcKeys = new HashSet<>();
        for (SsrcTransaction txn : all) {
            if (txn == null || txn.getCallId() == null) {
                continue;
            }
            // 年轻记录(新会话创建期)不碰;createTime=null 为存量老记录,视为陈旧
            if (txn.getCreateTime() != null && now - txn.getCreateTime() < ageMs) {
                keptSsrcKeys.add(key(txn));
                continue;
            }
            MediaServer ms = txn.getMediaServerId() == null ? null : mediaServerService.getOne(txn.getMediaServerId());
            if (ms == null) {
                // 媒体节点不可解析:保守跳过
                keptSsrcKeys.add(key(txn));
                continue;
            }
            Boolean ready = mediaServerService.isStreamReady(ms, txn.getApp(), txn.getStream());
            if (ready == null) {
                // 活性查询失败:保守跳过,下轮再试
                keptSsrcKeys.add(key(txn));
                continue;
            }
            if (ready) {
                // 流还活着:会话有效,保留
                keptSsrcKeys.add(key(txn));
                continue;
            }
            stale.add(txn);
        }

        int reaped = 0;
        for (SsrcTransaction txn : stale) {
            sessionManager.removeByCallId(txn.getCallId());
            String ssrcToRelease = Objects.requireNonNullElse(txn.getSsrcToRelease(), txn.getSsrc());
            if (ssrcToRelease != null && keptSsrcKeys.contains(key(txn))) {
                log.warn("[invite 收割] 仅清记录不还号(同号在册,防偷号): callId={} stream={}",
                        txn.getCallId(), txn.getStream());
            } else if (ssrcToRelease != null) {
                ssrcFactory.releaseSsrc(txn.getMediaServerId(), ssrcToRelease);
                log.warn("[invite 收割] 清记录+还号: callId={} stream={} ssrc={}",
                        txn.getCallId(), txn.getStream(), ssrcToRelease);
            } else {
                log.warn("[invite 收割] 清记录(无号可还): callId={} stream={}", txn.getCallId(), txn.getStream());
            }
            // 20261117 幽灵流修复②:补发 BYE 给设备(给设备"第二次停推机会")
            // 幽灵根因=竞态漏发 BYE→设备持续推流→ZLM 流名被占;收割器补发使幽灵在超龄阈值(10min)内消亡
            sendFallbackBye(txn);
            reaped++;
        }
        if (reaped > 0) {
            TOTAL_REAPED.addAndGet(reaped);
            log.warn("[invite 收割] 本轮完成: 会话总数 {} → 收割 {} | 累计收割 {} 次(非零=有路径漏还号被兜底,根因需追查)",
                    all.size(), reaped, TOTAL_REAPED.get());
        }
        return reaped;
    }

    private String key(SsrcTransaction txn) {
        String ssrc = Objects.requireNonNullElse(txn.getSsrcToRelease(), txn.getSsrc());
        return (txn.getMediaServerId() == null ? "" : txn.getMediaServerId()) + "|" + (ssrc == null ? "" : ssrc);
    }

    /**
     * 20261117 幽灵流修复②:向设备补发 BYE(收割超龄会话时).
     * 幽灵根因=竞速漏发 BYE→设备不知该停→持续推流→ZLM 流名被占.
     * 此处给设备"第二次停推机会":诚实固件收到即停,幽灵从物理层消亡.
     * 设备离线不发送;发送失败仅告警不阻断收割流程.
     */
    private void sendFallbackBye(SsrcTransaction txn) {
        if (txn.getDeviceId() == null || txn.getCallId() == null || txn.getStream() == null) {
            return;
        }
        try {
            com.genersoft.iot.vmp.gb28181.bean.Device device = deviceService.getDeviceByDeviceId(txn.getDeviceId());
            if (device == null || !device.isOnLine()) {
                return; // 设备离线,BYE 无意义
            }
            String channelId = txn.getStream().substring(txn.getStream().indexOf('_') + 1);
            cmder.streamByeCmd(device, channelId, txn.getApp(), txn.getStream(), txn.getCallId(), null);
            log.info("[invite 收割] 补发 BYE: device={} stream={} callId={}", txn.getDeviceId(), txn.getStream(), txn.getCallId());
        } catch (Exception e) {
            log.warn("[invite 收割] 补发 BYE 失败(容忍,收割继续): stream={} err={}", txn.getStream(), e.getMessage());
        }
    }
}
