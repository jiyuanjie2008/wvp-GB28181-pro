package com.genersoft.iot.vmp.gb28181.session;

import com.genersoft.iot.vmp.conf.SipConfig;
import com.genersoft.iot.vmp.conf.UserSetting;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用:以内存 Set 模拟 Redis 号池(SADD/SPOP/SCARD 原子语义),驱动真实 SSRCFactory。
 */
public final class InMemorySsrcPool {

    private static final String SSRC_INFO_KEY = "VMP_SSRC_INFO_";

    private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();
    private final SSRCFactory factory = new SSRCFactory();
    private final String serverId;

    @SuppressWarnings("unchecked")
    public InMemorySsrcPool(String serverId, String sipDomain) {
        this.serverId = serverId;
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        SetOperations<String, String> ops = mock(SetOperations.class);
        when(template.opsForSet()).thenReturn(ops);
        when(template.delete(anyString())).thenAnswer(inv -> sets.remove((String) inv.getArgument(0)) != null);
        when(template.hasKey(anyString())).thenAnswer(inv -> sets.containsKey((String) inv.getArgument(0)));
        when(ops.size(anyString())).thenAnswer(inv -> {
            Set<String> set = sets.get((String) inv.getArgument(0));
            return set == null ? 0L : (long) set.size();
        });
        when(ops.add(anyString(), any(String[].class))).thenAnswer(inv -> {
            Set<String> set = sets.computeIfAbsent((String) inv.getArgument(0),
                    k -> Collections.synchronizedSet(new HashSet<>()));
            long added = 0;
            for (String value : (String[]) inv.getRawArguments()[1]) {
                if (set.add(value)) {
                    added++;
                }
            }
            return added;
        });
        when(ops.pop(anyString())).thenAnswer(inv -> {
            Set<String> set = sets.get((String) inv.getArgument(0));
            if (set == null) {
                return null;
            }
            synchronized (set) {
                Iterator<String> it = set.iterator();
                if (!it.hasNext()) {
                    return null;
                }
                String value = it.next();
                it.remove();
                return value;
            }
        });

        SipConfig sipConfig = mock(SipConfig.class);
        when(sipConfig.getDomain()).thenReturn(sipDomain);
        UserSetting userSetting = new UserSetting();
        userSetting.setServerId(serverId);
        ReflectionTestUtils.setField(factory, "redisTemplate", template);
        ReflectionTestUtils.setField(factory, "sipConfig", sipConfig);
        ReflectionTestUtils.setField(factory, "userSetting", userSetting);
    }

    public SSRCFactory factory() {
        return factory;
    }

    public Set<String> pool(String mediaServerId) {
        Set<String> set = sets.get(SSRC_INFO_KEY + serverId + "_" + mediaServerId);
        if (set == null) {
            return Collections.emptySet();
        }
        synchronized (set) {
            return new HashSet<>(set);
        }
    }
}
