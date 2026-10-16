package com.alipay.antchain.bridge.relayer.core.service.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import com.alipay.antchain.bridge.relayer.dal.repository.impl.BlockchainIdleDCache;
import org.junit.Before;
import org.junit.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BlockchainIdleWakeupTest {
    private final TestCache cache = new TestCache();
    private final Map<String, String> redis = new ConcurrentHashMap<>();

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        when(client.getBucket(anyString(), any())).thenAnswer(call -> {
            String key = call.getArgument(0);
            RBucket<String> bucket = mock(RBucket.class);
            when(bucket.get()).thenAnswer(read -> redis.get(key));
            doAnswer(write -> { redis.put(key, write.getArgument(0)); return null; })
                    .when(bucket).set(anyString());
            return bucket;
        });
        ValidationWakeupTest.inject(cache, "redisson", client);
        ValidationWakeupTest.inject(cache, "idleTime", 10000L);
        for (String state : new String[]{"last_empty_am_pool_time", "last_empty_ucp_pool_time",
                "last_empty_am_send_queue_time", "last_empty_am_archive_time",
                "last_empty_oracle_pool_time", "last_empty_oracle_committer_time"}) {
            redis.put(BlockchainIdleDCache.genKey("mychain020", "chain", state),
                    String.valueOf(System.currentTimeMillis()));
        }
    }

    @Test
    public void lostWakeupRecoversByTimeEvenWithSparseScheduling() {
        assertFalse(cache.ifAMProcessIdle("mychain020", "chain"));
        for (int i = 0; i < 300; i++) { assertTrue(cache.ifAMProcessIdle("mychain020", "chain")); }
        cache.nanos.set(TimeUnit.MILLISECONDS.toNanos(999));
        assertTrue(cache.ifAMProcessIdle("mychain020", "chain"));
        cache.nanos.set(TimeUnit.SECONDS.toNanos(1));
        assertFalse(cache.ifAMProcessIdle("mychain020", "chain"));
        cache.nanos.set(TimeUnit.SECONDS.toNanos(50));
        assertFalse(cache.ifAMProcessIdle("mychain020", "chain"));
    }

    @Test
    public void proofReadyWakeupExitsIdleImmediately() {
        assertFalse(cache.ifAMProcessIdle("mychain020", "chain"));
        assertTrue(cache.ifAMProcessIdle("mychain020", "chain"));
        cache.setLastAMReceiveTime("mychain020", "chain");
        assertFalse(cache.ifAMProcessIdle("mychain020", "chain"));
    }

    @Test
    public void proofFinishingAfterTwentySecondsWakesTheActualIdleGateAfterCommit() throws Exception {
        redis.put(BlockchainIdleDCache.genKey("mychain020", "source-chain", "last_am_receive_time"),
                String.valueOf(System.currentTimeMillis() - 20000));
        redis.put(BlockchainIdleDCache.genKey("mychain020", "source-chain", "last_empty_am_pool_time"),
                String.valueOf(System.currentTimeMillis()));
        assertFalse(cache.ifAMProcessIdle("mychain020", "source-chain"));
        assertTrue(cache.ifAMProcessIdle("mychain020", "source-chain"));
        ValidationWakeupTest validation = new ValidationWakeupTest();
        validation.setUp();
        ValidationWakeupTest.inject(validation.service, "blockchainIdleDCache", cache);
        validation.service.wrapUCPTask("test-ucp").run();
        assertFalse(cache.ifAMProcessIdle("mychain020", "source-chain"));
    }

    @Test
    public void allIdleQueuesHaveIndependentBoundedRescans() {
        List<BiFunction<String, String, Boolean>> gates = new ArrayList<>();
        gates.add(cache::ifAMProcessIdle); gates.add(cache::ifUCPProcessIdle);
        gates.add(cache::ifAMCommitterIdle); gates.add(cache::ifAMArchiveIdle);
        gates.add(cache::ifOracleProcessIdle); gates.add(cache::ifOracleCommitterIdle);
        for (BiFunction<String, String, Boolean> gate : gates) {
            assertFalse(gate.apply("mychain020", "chain"));
            assertTrue(gate.apply("mychain020", "chain"));
        }
        cache.nanos.set(TimeUnit.SECONDS.toNanos(1));
        for (BiFunction<String, String, Boolean> gate : gates) {
            assertFalse(gate.apply("mychain020", "chain"));
        }
    }

    @Test
    public void concurrentPollersClaimOnlyOneFallbackScan() throws Exception {
        assertFalse(cache.ifAMProcessIdle("mychain020", "chain"));
        cache.nanos.set(TimeUnit.SECONDS.toNanos(1));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) { tasks.add(() -> cache.ifAMProcessIdle("mychain020", "chain")); }
            int scans = 0;
            for (Future<Boolean> result : pool.invokeAll(tasks)) { if (!result.get()) { scans++; } }
            assertEquals(1, scans);
        } finally { pool.shutdownNow(); }
    }

    private static class TestCache extends BlockchainIdleDCache {
        final AtomicLong nanos = new AtomicLong();
        protected long nanoTime() { return nanos.get(); }
    }
}
