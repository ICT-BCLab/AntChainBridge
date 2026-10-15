package org.antchainbridge.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class OpenLoopWorkload {

    public static final int FORMAL_RATE_PER_SECOND = 5;
    public static final int FORMAL_DURATION_SECONDS = 30;
    public static final long FORMAL_INTERVAL_MILLIS = 200L;
    public static final long MAX_ACCEPTABLE_LATENESS_MILLIS = 200L;

    interface TimeSource {
        long currentTimeMillis();

        long nanoTime();

        void waitUntil(long deadlineNanos) throws InterruptedException;
    }

    private static final TimeSource SYSTEM_TIME = new TimeSource() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public void waitUntil(long deadlineNanos) throws InterruptedException {
            while (true) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(remaining);
            }
        }
    };

    private final TimeSource timeSource;

    public OpenLoopWorkload() {
        this(SYSTEM_TIME);
    }

    OpenLoopWorkload(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public RunResult run(ExperimentConfig config, TransactionSender sender) throws Exception {
        int rate = config.integer("workload.rate", FORMAL_RATE_PER_SECOND);
        int durationSeconds = config.integer("workload.duration.seconds", FORMAL_DURATION_SECONDS);
        int workers = config.integer("workload.workers", 16);
        if (rate <= 0 || durationSeconds <= 0 || workers <= 0) {
            throw new IllegalArgumentException("rate, duration and workers must be positive");
        }
        if (1000 % rate != 0) {
            throw new IllegalArgumentException("workload.rate must evenly divide 1000ms");
        }
        long intervalMillis = 1000L / rate;
        int sampleCount = rate * durationSeconds;

        boolean formal = config.bool("experiment.formal", false);
        if (formal && (rate != FORMAL_RATE_PER_SECOND || durationSeconds != FORMAL_DURATION_SECONDS)) {
            throw new IllegalArgumentException("formal runs require 5 TPS for 30 seconds");
        }
        if (formal) {
            long senderOffset = config.longValue("clock.offset.ms", Long.MIN_VALUE);
            long relayerOffset = config.longValue("relayer.clock.offset.ms", Long.MIN_VALUE);
            if (senderOffset == Long.MIN_VALUE || relayerOffset == Long.MIN_VALUE
                    || Math.abs(senderOffset) > 20L || Math.abs(relayerOffset) > 20L) {
                throw new IllegalArgumentException(
                        "formal runs require sender and Relayer clock offsets with absolute value <= 20ms"
                );
            }
            String pluginArtifact = config.optional("plugin.artifact", "");
            if (pluginArtifact.isEmpty() || !Files.isRegularFile(Path.of(pluginArtifact))) {
                throw new IllegalArgumentException("formal runs require an existing plugin.artifact file");
            }
        }

        String experimentId = config.required("experiment.id");
        String scheme = config.required("experiment.scheme");
        String route = config.required("experiment.route");
        int round = config.integer("experiment.round", 1);
        String sourceProduct = config.required("source.product");
        String sourceBlockchainId = config.required("source.blockchain.id");
        Path output = Path.of(config.required("output.dir"));
        Files.createDirectories(output);

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<SenderRecord>> futures = new ArrayList<>(sampleCount);
        long startEpochMillis = timeSource.currentTimeMillis();
        long startNanos = timeSource.nanoTime();

        try (JsonlWriter writer = new JsonlWriter(output.resolve("sender.jsonl"))) {
            for (int index = 0; index < sampleCount; index++) {
                final int sampleIndex = index;
                long offsetMillis = index * intervalMillis;
                long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(offsetMillis);
                timeSource.waitUntil(deadlineNanos);
                byte[] payload = ExperimentPayload.create(experimentId, scheme, route, round, sampleIndex);
                long scheduledAtEpochMillis = startEpochMillis + offsetMillis;
                futures.add(executor.submit(() -> {
                    long t0EpochMillis = timeSource.currentTimeMillis();
                    long latenessMillis = Math.max(0L, t0EpochMillis - scheduledAtEpochMillis);
                    SenderRecord record = sendOne(
                            sender,
                            experimentId,
                            scheme,
                            route,
                            round,
                            sampleIndex,
                            scheduledAtEpochMillis,
                            t0EpochMillis,
                            latenessMillis,
                            sourceProduct,
                            sourceBlockchainId,
                            payload
                    );
                    writer.write(record);
                    return record;
                }));
            }
            executor.shutdown();

            List<SenderRecord> records = new ArrayList<>(sampleCount);
            for (Future<SenderRecord> future : futures) {
                records.add(future.get());
            }
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                throw new IllegalStateException("source transaction workers did not terminate");
            }
            writeManifest(output.resolve("run-manifest.json"), config, sampleCount, intervalMillis, records);
            return new RunResult(records, intervalMillis);
        } finally {
            executor.shutdownNow();
        }
    }

    private SenderRecord sendOne(
            TransactionSender sender,
            String experimentId,
            String scheme,
            String route,
            int round,
            int sampleIndex,
            long scheduledAt,
            long t0,
            long lateness,
            String sourceProduct,
            String sourceBlockchainId,
            byte[] payload
    ) {
        try {
            String nativeHash = sender.send(payload);
            if (nativeHash == null || nativeHash.trim().isEmpty()) {
                throw new IllegalStateException("source chain returned an empty transaction hash");
            }
            return SenderRecord.success(
                    experimentId, scheme, route, round, sampleIndex, scheduledAt, t0, lateness,
                    sourceProduct, sourceBlockchainId, nativeHash
            );
        } catch (Exception e) {
            return SenderRecord.failure(
                    experimentId, scheme, route, round, sampleIndex, scheduledAt, t0, lateness,
                    sourceProduct, sourceBlockchainId,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()
            );
        }
    }

    private void writeManifest(
            Path path,
            ExperimentConfig config,
            int sampleCount,
            long intervalMillis,
            List<SenderRecord> records
    ) throws IOException {
        long late = records.stream().filter(record -> record.scheduleLatenessMs > MAX_ACCEPTABLE_LATENESS_MILLIS).count();
        String json = "{"
                + field("schemaVersion", "1", false)
                + field("experimentId", quote(config.required("experiment.id")), true)
                + field("scheme", quote(config.required("experiment.scheme")), true)
                + field("route", quote(config.required("experiment.route")), true)
                + field("round", Integer.toString(config.integer("experiment.round", 1)), true)
                + field("samples", Integer.toString(sampleCount), true)
                + field("intervalMillis", Long.toString(intervalMillis), true)
                + field("ratePerSecond", Integer.toString(config.integer("workload.rate", FORMAL_RATE_PER_SECOND)), true)
                + field("durationSeconds", Integer.toString(config.integer("workload.duration.seconds", FORMAL_DURATION_SECONDS)), true)
                + field("terminalWaitSeconds", Long.toString(config.longValue("terminal.wait.seconds", 900L)), true)
                + field("lateSamples", Long.toString(late), true)
                + field("formal", Boolean.toString(config.bool("experiment.formal", false)), true)
                + field("protocol", quote(config.optional("experiment.protocol", "unordered-sdp-v1")), true)
                + field("hostName", quote(hostName()), true)
                + field("clockOffsetMillis", config.optional("clock.offset.ms", "unknown").matches("-?\\d+")
                        ? config.optional("clock.offset.ms", "unknown") : quote(config.optional("clock.offset.ms", "unknown")), true)
                + field("relayerHostName", quote(config.optional("relayer.host.name", "unknown")), true)
                + field("relayerClockOffsetMillis", config.optional("relayer.clock.offset.ms", "unknown").matches("-?\\d+")
                        ? config.optional("relayer.clock.offset.ms", "unknown")
                        : quote(config.optional("relayer.clock.offset.ms", "unknown")), true)
                + field("sourceProduct", quote(config.required("source.product")), true)
                + field("sourceBlockchainId", quote(config.required("source.blockchain.id")), true)
                + field("codeRevision", quote(config.optional("code.revision", "unknown")), true)
                + field("pluginArtifact", quote(config.optional("plugin.artifact", "unknown")), true)
                + field("pluginChecksum", quote(pluginChecksum(config)), true)
                + "}";
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
            return "unknown";
        }
    }

    private static String pluginChecksum(ExperimentConfig config) throws IOException {
        String artifact = config.optional("plugin.artifact", "");
        if (artifact.isEmpty()) {
            return config.optional("plugin.checksum", "unknown");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(Path.of(artifact))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String field(String key, String value, boolean leadingComma) {
        return (leadingComma ? "," : "") + quote(key) + ":" + value;
    }

    static String normaliseHashKey(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("0x") || result.startsWith("0X")) {
            result = result.substring(2);
        }
        return result.toLowerCase(Locale.ROOT);
    }

    static String quote(String value) {
        String escaped = (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "\"" + escaped + "\"";
    }

    public static final class RunResult {
        private final List<SenderRecord> records;
        private final long intervalMillis;

        RunResult(List<SenderRecord> records, long intervalMillis) {
            this.records = records;
            this.intervalMillis = intervalMillis;
        }

        public List<SenderRecord> getRecords() {
            return records;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }
    }

    private static final class JsonlWriter implements AutoCloseable {
        private final BufferedWriter writer;

        private JsonlWriter(Path path) throws IOException {
            this.writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        }

        private synchronized void write(SenderRecord record) throws IOException {
            writer.write(record.toJson());
            writer.newLine();
            writer.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            writer.close();
        }
    }

    public static final class SenderRecord {
        private final String experimentId;
        private final String scheme;
        private final String route;
        private final int round;
        private final String sampleId;
        private final long scheduledAtEpochMs;
        private final long t0EpochMs;
        private final long scheduleLatenessMs;
        private final String sourceProduct;
        private final String sourceBlockchainId;
        private final String sourceTxHashNative;
        private final String sourceTxHashKey;
        private final String sendStatus;
        private final String error;

        private SenderRecord(
                String experimentId, String scheme, String route, int round, int sampleIndex,
                long scheduledAtEpochMs, long t0EpochMs, long scheduleLatenessMs,
                String sourceProduct, String sourceBlockchainId, String sourceTxHashNative,
                String sendStatus, String error
        ) {
            this.experimentId = experimentId;
            this.scheme = scheme;
            this.route = route;
            this.round = round;
            this.sampleId = ExperimentPayload.sampleId(experimentId, scheme, route, round, sampleIndex);
            this.scheduledAtEpochMs = scheduledAtEpochMs;
            this.t0EpochMs = t0EpochMs;
            this.scheduleLatenessMs = scheduleLatenessMs;
            this.sourceProduct = sourceProduct;
            this.sourceBlockchainId = sourceBlockchainId;
            this.sourceTxHashNative = sourceTxHashNative == null ? "" : sourceTxHashNative;
            this.sourceTxHashKey = normaliseHashKey(sourceTxHashNative);
            this.sendStatus = sendStatus;
            this.error = error == null ? "" : error;
        }

        static SenderRecord success(
                String experimentId, String scheme, String route, int round, int sampleIndex,
                long scheduledAtEpochMs, long t0EpochMs, long scheduleLatenessMs,
                String sourceProduct, String sourceBlockchainId, String sourceTxHashNative
        ) {
            return new SenderRecord(
                    experimentId, scheme, route, round, sampleIndex, scheduledAtEpochMs, t0EpochMs,
                    scheduleLatenessMs, sourceProduct, sourceBlockchainId, sourceTxHashNative, "SENT", ""
            );
        }

        static SenderRecord failure(
                String experimentId, String scheme, String route, int round, int sampleIndex,
                long scheduledAtEpochMs, long t0EpochMs, long scheduleLatenessMs,
                String sourceProduct, String sourceBlockchainId, String error
        ) {
            return new SenderRecord(
                    experimentId, scheme, route, round, sampleIndex, scheduledAtEpochMs, t0EpochMs,
                    scheduleLatenessMs, sourceProduct, sourceBlockchainId, "", "SEND_FAILED", error
            );
        }

        String toJson() {
            return "{"
                    + field("experimentId", quote(experimentId), false)
                    + field("scheme", quote(scheme), true)
                    + field("route", quote(route), true)
                    + field("round", Integer.toString(round), true)
                    + field("sampleId", quote(sampleId), true)
                    + field("scheduledAtEpochMs", Long.toString(scheduledAtEpochMs), true)
                    + field("t0EpochMs", Long.toString(t0EpochMs), true)
                    + field("scheduleLatenessMs", Long.toString(scheduleLatenessMs), true)
                    + field("sourceProduct", quote(sourceProduct), true)
                    + field("sourceBlockchainId", quote(sourceBlockchainId), true)
                    + field("sourceTxHashNative", quote(sourceTxHashNative), true)
                    + field("sourceTxHashKey", quote(sourceTxHashKey), true)
                    + field("sendStatus", quote(sendStatus), true)
                    + field("error", quote(error), true)
                    + "}";
        }

        public long getScheduledAtEpochMs() {
            return scheduledAtEpochMs;
        }

        public long getScheduleLatenessMs() {
            return scheduleLatenessMs;
        }

        public String getSampleId() {
            return sampleId;
        }

        public String getSendStatus() {
            return sendStatus;
        }
    }
}
