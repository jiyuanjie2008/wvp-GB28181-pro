package com.genersoft.iot.vmp.gb28181.session;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 号池并发重复还号行为锁定(2026-11-18):多条清理路径(超时/停止/设备BYE/兜底BYE/收割器)
 * 可能并发还同一个号。还号必须幂等——同号无论并发还多少次,池中只有一份,
 * 且之后整池取号不会出现同号被分给两路流。
 */
class SSRCFactoryConcurrentReleaseTest {

    private static final String MEDIA_ID = "media-1";
    private static final int POOL_SIZE = 9999;

    @Test
    void concurrentDuplicateReleaseKeepsSinglePoolEntry() throws Exception {
        InMemorySsrcPool pool = new InMemorySsrcPool("000000", "3402000000");
        SSRCFactory factory = pool.factory();
        factory.initMediaServerSSRC(MEDIA_ID, null);
        assertEquals(POOL_SIZE, pool.pool(MEDIA_ID).size());

        List<String> allocated = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            allocated.add(i % 2 == 0 ? factory.getPlaySsrc(MEDIA_ID) : factory.getPlayBackSsrc(MEDIA_ID));
        }
        assertEquals(POOL_SIZE - 200, pool.pool(MEDIA_ID).size());

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            executor.execute(() -> {
                try {
                    start.await();
                    for (String ssrc : allocated) {
                        factory.releaseSsrc(MEDIA_ID, ssrc);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdownNow();

        assertEquals(POOL_SIZE, pool.pool(MEDIA_ID).size());

        // 整池并发取空:任何号不得被分出两次
        Set<String> handedOut = ConcurrentHashMap.newKeySet();
        ExecutorService drain = Executors.newFixedThreadPool(threads);
        CountDownLatch drainDone = new CountDownLatch(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            drain.execute(() -> {
                try {
                    String sn = factory.getPlaySsrc(MEDIA_ID).substring(1);
                    assertTrue(handedOut.add(sn), "同一号被分出两次: " + sn);
                } finally {
                    drainDone.countDown();
                }
            });
        }
        assertTrue(drainDone.await(60, TimeUnit.SECONDS));
        drain.shutdownNow();
        assertEquals(POOL_SIZE, handedOut.size());
        assertEquals(0, pool.pool(MEDIA_ID).size());
    }
}
