package com.alipay.antchain.bridge.relayer.core.service.anchor.tasks;

import java.util.concurrent.CompletableFuture;

import com.alipay.antchain.bridge.relayer.commons.model.BlockchainMeta;
import com.alipay.antchain.bridge.relayer.core.service.anchor.context.AnchorProcessContext;
import com.alipay.antchain.bridge.relayer.core.types.blockchain.AbstractBlockchainClient;
import com.alipay.antchain.bridge.relayer.core.types.blockchain.HeterogeneousBlock;
import org.junit.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CachedBlockQueueTest {

    @Test
    @SuppressWarnings("unchecked")
    public void reloadsOldDomainBlockFromTheChainAfterDomainSwitch() {
        AnchorProcessContext context = mock(AnchorProcessContext.class);
        AbstractBlockchainClient client = mock(AbstractBlockchainClient.class);
        RedissonClient redis = mock(RedissonClient.class);
        RBucket<byte[]> bucket = mock(RBucket.class);
        RFuture<Void> write = mock(RFuture.class);
        when(write.exceptionally(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(context.getBlockchainDomain()).thenReturn("idea20");
        when(context.getAnchorProduct()).thenReturn("dioxide2");
        when(context.getAnchorBlockchainId()).thenReturn("diox20.id");
        when(context.getBlockchainMeta()).thenReturn(mock(BlockchainMeta.class));
        when(context.getBlockchainClient()).thenReturn(client);
        when(redis.<byte[]>getBucket(eq("dioxide2^diox20.id^42"), eq(ByteArrayCodec.INSTANCE))).thenReturn(bucket);
        when(bucket.setAsync(any(), anyLong(), any())).thenReturn(write);
        when(bucket.get()).thenReturn(block("diox20").encode());
        when(client.getEssentialBlockByHeight(42)).thenReturn(block("idea20"));

        CachedBlockQueue queue = new CachedBlockQueue(context, redis, 10, 1000);
        assertEquals("idea20", queue.getBlockFromQueue(42).getDomain());
        assertEquals("idea20", queue.getBlockFromQueue(42).getDomain());
        verify(client).getEssentialBlockByHeight(42);
    }

    private HeterogeneousBlock block(String domain) {
        HeterogeneousBlock block = new HeterogeneousBlock();
        block.setProduct("dioxide2");
        block.setBlockchainId("diox20.id");
        block.setDomain(domain);
        block.setHeight(42);
        return block;
    }
}
