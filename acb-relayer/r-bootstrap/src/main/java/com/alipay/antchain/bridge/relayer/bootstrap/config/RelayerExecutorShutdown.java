package com.alipay.antchain.bridge.relayer.bootstrap.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alipay.antchain.bridge.relayer.core.manager.bbc.IBBCPluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Drain database users during ContextClosedEvent, before Spring destroys resources. */
@Component
public class RelayerExecutorShutdown implements ApplicationListener<ContextClosedEvent>, Ordered {
    private static final Logger LOG = LoggerFactory.getLogger(RelayerExecutorShutdown.class);
    private final ApplicationContext context;
    private final Map<String, ExecutorService> executors;
    private final long timeoutSeconds;
    private final AtomicBoolean closing = new AtomicBoolean();

    public RelayerExecutorShutdown(ApplicationContext context, Map<String, ExecutorService> executors,
                                   IBBCPluginManager pluginManager,
                                   @Value("${relayer.shutdown.timeout_seconds:60}") long timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("shutdown timeout must be positive");
        this.context = context;
        this.executors = new LinkedHashMap<>(executors);
        this.executors.putAll(pluginManager.getManagedExecutors());
        this.timeoutSeconds = timeoutSeconds;
        LOG.info("Relayer shutdown coordinator tracks {} executors", this.executors.size());
    }

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() != context || !closing.compareAndSet(false, true)) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        boolean interrupted = false;
        boolean drained = true;
        try {
            for (List<ExecutorService> phase : phases()) {
                for (ExecutorService executor : phase) shutdown(executor);
                for (ExecutorService executor : phase) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        drained = false;
                        break;
                    }
                }
                if (!drained) break;
            }
        } catch (InterruptedException e) {
            interrupted = true;
            drained = false;
        } catch (RuntimeException e) {
            LOG.error("Relayer executor shutdown failed during drain", e);
            drained = false;
        }
        if (!drained) {
            LOG.error("Relayer executor shutdown failed to drain work within its bounded wait");
            for (ExecutorService executor : executors.values()) executor.shutdownNow();
            long forcedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (ExecutorService executor : executors.values()) {
                while (!executor.isTerminated()) {
                    long remaining = forcedDeadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
        }
        long alive = executors.values().stream().filter(e -> !e.isTerminated()).count();
        if (drained && alive == 0) LOG.info("Relayer executor shutdown complete: {} pools terminated", executors.size());
        else LOG.error("Relayer executor shutdown failed: {} pools remain active", alive);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void shutdown(ExecutorService executor) {
        if (executor instanceof ScheduledThreadPoolExecutor) {
            ScheduledThreadPoolExecutor scheduled = (ScheduledThreadPoolExecutor) executor;
            scheduled.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            scheduled.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
        executor.shutdown();
    }

    private List<List<ExecutorService>> phases() {
        Map<String, ExecutorService> remaining = new LinkedHashMap<>(executors);
        List<ExecutorService> producers = take(remaining, "distributedTaskEngineScheduleThreadsPool",
                "markTaskProcessEngineScheduleThreadsPool", "pluginManagerHeartbeatExecutorService",
                "wsRelayerServerExecutorService");
        List<ExecutorService> dispatch = take(remaining, "anchorScheduleTaskExecutorThreadsPool",
                "committerScheduleTaskExecutorThreadsPool", "processScheduleTaskExecutorThreadsPool",
                "validationScheduleTaskExecutorThreadsPool", "confirmScheduleTaskExecutorThreadsPool",
                "archiveScheduleTaskExecutorThreadsPool", "deployScheduleTaskExecutorThreadsPool",
                "baseScheduleBizTaskExecutorThreadsPool", "domainRouterScheduleTaskExecutorThreadsPool",
                "reliableProcessTaskExecutorThreadsPool");
        List<ExecutorService> clients = take(remaining, "wsRelayerClientThreadsPool", "pluginManagerClientExecutorService");
        // Downstream block/validation/process/commit/confirm work can complete while
        // its dispatch parent drains; client callbacks remain available until last.
        return Arrays.asList(producers, dispatch, new ArrayList<>(remaining.values()), clients);
    }

    private List<ExecutorService> take(Map<String, ExecutorService> values, String... names) {
        List<ExecutorService> result = new ArrayList<>();
        for (String name : names) {
            ExecutorService executor = values.remove(name);
            if (executor != null) result.add(executor);
        }
        return result;
    }
}
