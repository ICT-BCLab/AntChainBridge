package org.antchainbridge.experiment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class OpenLoopWorkloadTest {

    @Test
    public void formalScheduleHas150Unique512ByteSamplesWithoutRetries() throws Exception {
        Path output = Files.createTempDirectory("paper-experiment-test");
        Path properties = properties(output, 5, 30);
        ExperimentConfig config = ExperimentConfig.load(properties);
        FakeTime time = new FakeTime(1700000000000L);
        AtomicInteger calls = new AtomicInteger();
        Set<String> payloads = java.util.Collections.synchronizedSet(new HashSet<>());

        OpenLoopWorkload.RunResult result = new OpenLoopWorkload(time).run(config, payload -> {
            Assert.assertEquals(512, payload.length);
            payloads.add(new String(payload, StandardCharsets.UTF_8));
            int call = calls.getAndIncrement();
            if (call == 7) {
                throw new IOException("planned failure");
            }
            return "0x" + Integer.toHexString(call);
        });

        Assert.assertEquals(150, result.getRecords().size());
        Assert.assertEquals(150, calls.get());
        Assert.assertEquals(150, payloads.size());
        Assert.assertEquals(200L, result.getIntervalMillis());
        Assert.assertEquals(1700000000000L, result.getRecords().get(0).getScheduledAtEpochMs());
        Assert.assertEquals(1700000029800L, result.getRecords().get(149).getScheduledAtEpochMs());
        Assert.assertEquals("SEND_FAILED", result.getRecords().get(7).getSendStatus());
        Assert.assertTrue(Files.size(output.resolve("sender.jsonl")) > 0);
    }

    @Test
    public void payloadIsExactly512BytesAndContainsUniqueId() {
        byte[] payload = ExperimentPayload.create("exp", "Xun", "ETH-MYC", 2, 17);
        Assert.assertEquals(512, payload.length);
        Assert.assertTrue(new String(payload, StandardCharsets.UTF_8)
                .startsWith("ACBEXP1|exp-Xun-ETH-MYC-r2-s17|"));
    }

    @Test
    public void formalRunRejectsMissingRelayerClockOffset() throws Exception {
        Path output = Files.createTempDirectory("paper-experiment-clock-test");
        Path path = properties(output, 5, 30);
        Files.write(
                path,
                ("experiment.formal=true\nclock.offset.ms=0\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND
        );
        try {
            new OpenLoopWorkload(new FakeTime(1700000000000L)).run(
                    ExperimentConfig.load(path), payload -> "0x01"
            );
            Assert.fail("expected missing Relayer clock offset to be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("Relayer clock offsets"));
        }
    }

    private Path properties(Path output, int rate, int duration) throws IOException {
        Path path = output.resolve("experiment.properties");
        String value = "experiment.id=exp\n"
                + "experiment.scheme=Xun\n"
                + "experiment.route=ETH-MYC\n"
                + "experiment.round=1\n"
                + "source.product=ethereum3\n"
                + "source.blockchain.id=eth.id\n"
                + "output.dir=" + output + "\n"
                + "workload.rate=" + rate + "\n"
                + "workload.duration.seconds=" + duration + "\n"
                + "workload.workers=4\n";
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static final class FakeTime implements OpenLoopWorkload.TimeSource {
        private final long epochBase;
        private long nanos;

        private FakeTime(long epochBase) {
            this.epochBase = epochBase;
        }

        @Override
        public long currentTimeMillis() {
            return epochBase + TimeUnit.NANOSECONDS.toMillis(nanos);
        }

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void waitUntil(long deadlineNanos) {
            nanos = deadlineNanos;
        }
    }
}
