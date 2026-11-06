package com.alipay.antchain.bridge.relayer.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alipay.antchain.bridge.relayer.core.manager.bbc.GRpcBBCPluginManager;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.GenericApplicationContext;

public class RelayerExecutorShutdownTest {
    private GRpcBBCPluginManager manager(ExecutorService client, ScheduledExecutorService heartbeat) {
        return new GRpcBBCPluginManager(null, null, null, null, client, heartbeat, 1000, 3);
    }

    @Test
    public void parentAndDownstreamWorkFinishBeforeDatabaseResourceCloses() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        AtomicBoolean databaseOpen = new AtomicBoolean(true), wrote = new AtomicBoolean(false);
        context.getDefaultListableBeanFactory().registerSingleton("database", databaseOpen);
        context.getDefaultListableBeanFactory().registerDisposableBean("database", () -> databaseOpen.set(false));
        CountDownLatch producerStopped = new CountDownLatch(1), entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ScheduledThreadPoolExecutor producer = new ScheduledThreadPoolExecutor(1) {
            @Override public void shutdown() { super.shutdown(); producerStopped.countDown(); }
        };
        ExecutorService parent = Executors.newSingleThreadExecutor(), downstream = Executors.newSingleThreadExecutor();
        ExecutorService client = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor heartbeat = new ScheduledThreadPoolExecutor(1);
        Map<String, ExecutorService> pools = new LinkedHashMap<>();
        pools.put("distributedTaskEngineScheduleThreadsPool", producer);
        pools.put("anchorScheduleTaskExecutorThreadsPool", parent);
        pools.put("blockSyncTaskThreadsPool", downstream);
        context.addApplicationListener(new RelayerExecutorShutdown(context, pools, manager(client, heartbeat), 5));
        context.refresh();
        Future<?> work = parent.submit(() -> {
            entered.countDown();
            try {
                release.await();
                downstream.submit(() -> { if (databaseOpen.get()) wrote.set(true); }).get();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            Assert.assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> closed = closer.submit(context::close);
            Assert.assertTrue(producerStopped.await(3, TimeUnit.SECONDS));
            Assert.assertTrue(databaseOpen.get());
            release.countDown();
            closed.get(5, TimeUnit.SECONDS); work.get(1, TimeUnit.SECONDS);
            Assert.assertTrue(wrote.get()); Assert.assertFalse(databaseOpen.get());
            Assert.assertTrue(client.isTerminated()); Assert.assertTrue(heartbeat.isTerminated());
        } finally {
            release.countDown(); context.close();
            for (ExecutorService pool : pools.values()) pool.shutdownNow();
            client.shutdownNow(); heartbeat.shutdownNow(); closer.shutdownNow();
        }
    }

    @Test
    public void bothEngineSchedulersAndInternalHeartbeatCancelFuturePolls() {
        GenericApplicationContext context = new GenericApplicationContext();
        ScheduledThreadPoolExecutor engine = new ScheduledThreadPoolExecutor(1), marks = new ScheduledThreadPoolExecutor(1), heartbeat = new ScheduledThreadPoolExecutor(1);
        ExecutorService client = Executors.newSingleThreadExecutor();
        ScheduledFuture<?> pending = heartbeat.schedule(() -> Assert.fail("delayed heartbeat must not run"), 30, TimeUnit.SECONDS);
        Map<String, ExecutorService> pools = new LinkedHashMap<>();
        pools.put("distributedTaskEngineScheduleThreadsPool", engine); pools.put("markTaskProcessEngineScheduleThreadsPool", marks);
        context.addApplicationListener(new RelayerExecutorShutdown(context, pools, manager(client, heartbeat), 2));
        context.refresh(); context.close();
        Assert.assertTrue(engine.isTerminated()); Assert.assertTrue(marks.isTerminated());
        Assert.assertTrue(heartbeat.isTerminated()); Assert.assertTrue(pending.isCancelled()); Assert.assertTrue(client.isTerminated());
    }

    @Test
    public void childContextCloseDoesNotStopParentExecutors() {
        GenericApplicationContext parent = new GenericApplicationContext(), child = new GenericApplicationContext(parent);
        ExecutorService worker = Executors.newSingleThreadExecutor(), client = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor heartbeat = new ScheduledThreadPoolExecutor(1);
        Map<String, ExecutorService> pools = new LinkedHashMap<>(); pools.put("processServiceThreadsPool", worker);
        RelayerExecutorShutdown shutdown = new RelayerExecutorShutdown(parent, pools, manager(client, heartbeat), 2);
        parent.addApplicationListener(shutdown); parent.refresh(); child.refresh(); child.close();
        Assert.assertFalse(worker.isShutdown());
        parent.close(); shutdown.onApplicationEvent(new ContextClosedEvent(parent));
        Assert.assertTrue(worker.isTerminated());
    }

    @Test
    public void boundedCancellationStillLetsInterruptedWorkObserveOpenResources() throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        AtomicBoolean databaseOpen = new AtomicBoolean(true), interruptedWithDatabaseOpen = new AtomicBoolean(false);
        context.getDefaultListableBeanFactory().registerSingleton("database", databaseOpen);
        context.getDefaultListableBeanFactory().registerDisposableBean("database", () -> databaseOpen.set(false));
        ExecutorService worker = Executors.newSingleThreadExecutor(), client = Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor heartbeat = new ScheduledThreadPoolExecutor(1);
        Map<String, ExecutorService> pools = new LinkedHashMap<>(); pools.put("processServiceThreadsPool", worker);
        context.addApplicationListener(new RelayerExecutorShutdown(context, pools, manager(client, heartbeat), 1));
        context.refresh(); CountDownLatch entered = new CountDownLatch(1);
        worker.submit(() -> { entered.countDown(); try { new CountDownLatch(1).await(); } catch (InterruptedException e) { interruptedWithDatabaseOpen.set(databaseOpen.get()); } });
        Assert.assertTrue(entered.await(3, TimeUnit.SECONDS));
        context.close();
        Assert.assertTrue(worker.isTerminated()); Assert.assertTrue(interruptedWithDatabaseOpen.get()); Assert.assertFalse(databaseOpen.get());
    }
}
