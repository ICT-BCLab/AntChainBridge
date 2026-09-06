package com.alipay.antchain.bridge.plugins.lib.transactions;

import org.junit.*;
import java.sql.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class JdbcTransactionCoordinatorTest {
    private static final byte[] PAYLOAD = new byte[]{1, 2, 3};
    private String network;
    private JdbcTransactionCoordinator coordinator;
    private JdbcTransactionCoordinator.Connections connections;

    @Before public void setup() throws Exception {
        String url = System.getProperty("isn.test.jdbc");
        Assume.assumeTrue("Use a disposable MySQL database with the coordinator schema installed", url != null);
        Class.forName("com.mysql.cj.jdbc.Driver");
        connections = () -> DriverManager.getConnection(url, "root", "");
        network = "test-" + UUID.randomUUID();
        coordinator = new JdbcTransactionCoordinator(connections, network, "checkpoint");
    }

    private static class Node implements JdbcTransactionCoordinator.Transport {
        final Set<Long> submitted = ConcurrentHashMap.newKeySet();
        final AtomicInteger composeCalls = new AtomicInteger();
        volatile boolean failSign;
        volatile boolean loseResponse;
        volatile String checkpoint = "checkpoint";
        long nodeIsn = 181;
        long witnessTime = 70000;
        public long checkpointTimestamp(long height, String expectedHash) {
            if (!checkpoint.equals(expectedHash)) { throw new IllegalStateException("witness changed"); }
            return witnessTime;
        }
        public String checkpoint() { return checkpoint; }
        public long currentIsn(String account) { return nodeIsn; }
        public byte[] composeAndSign(long isn) throws Exception {
            composeCalls.incrementAndGet();
            if (failSign) { throw new Exception("signing unavailable"); }
            return ByteBuffer.allocate(8).putLong(isn).array();
        }
        public String broadcast(byte[] bytes) throws Exception {
            long isn = ByteBuffer.wrap(bytes).getLong();
            submitted.add(isn); // the node deduplicates identical signed bytes
            if (loseResponse) { loseResponse = false; throw new java.net.SocketTimeoutException(); }
            return "tx-" + isn;
        }
    }

    @Test public void concurrentClientsShareOneAccountWithoutLosingConcurrencyAcrossAccounts() throws Exception {
        Node node = new Node();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 64; i++) {
                final String id = "op-" + i;
                futures.add(pool.submit(() -> new JdbcTransactionCoordinator(connections, network, "checkpoint")
                        .submit(id, "account", PAYLOAD, node)));
            }
            Set<String> hashes = new HashSet<>();
            for (Future<String> f : futures) { hashes.add(f.get(30, TimeUnit.SECONDS)); }
            assertEquals(64, hashes.size());
            assertEquals(64, node.submitted.size());
            assertTrue(hashes.contains("tx-181")); assertTrue(hashes.contains("tx-244"));
            assertEquals("tx-181", coordinator.submit("different-account", "other", PAYLOAD, new Node()));
        } finally { pool.shutdownNow(); }
    }

    @Test public void responseLossAndRestartReuseSignedBytes() throws Exception {
        Node node = new Node(); node.loseResponse = true;
        try { coordinator.submit("stable", "account", PAYLOAD, node); fail(); }
        catch (java.net.SocketTimeoutException expected) { }
        JdbcTransactionCoordinator restarted = new JdbcTransactionCoordinator(connections, network, "checkpoint");
        assertEquals("tx-181", restarted.submit("stable", "account", PAYLOAD, node));
        assertEquals("tx-181", restarted.submit("stable", "account", PAYLOAD, node));
        assertEquals(1, node.composeCalls.get()); assertEquals(1, node.submitted.size());
        assertEquals("tx-182", restarted.submit("new-intent-same-payload", "account", PAYLOAD, node));
    }

    @Test public void signingFailureRollsBackAndIdentityConflictsFailClosed() throws Exception {
        Node node = new Node(); node.failSign = true;
        try { coordinator.submit("stable", "account", PAYLOAD, node); fail(); } catch (Exception expected) { }
        node.failSign = false;
        assertEquals("tx-181", coordinator.submit("stable", "account", PAYLOAD, node));
        try { coordinator.submit("stable", "account", new byte[]{9}, node); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("identity")); }
        node.checkpoint = "another-chain";
        try { coordinator.submit("new", "account", PAYLOAD, node); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("checkpoint")); }
    }

    @Test public void queryMailboxLockCoversReadAfterWrite() throws Exception {
        AtomicInteger mailbox = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                final int own = i;
                futures.add(pool.submit(() -> coordinator.withQueryLock("sdp|account", () -> {
                    mailbox.set(own); Thread.sleep(5); return mailbox.get();
                })));
            }
            for (int i = 0; i < futures.size(); i++) { assertEquals(i, (int) futures.get(i).get()); }
        } finally { pool.shutdownNow(); }
    }

    @Test public void databaseFailureDoesNotComposeOrBroadcast() throws Exception {
        Node node = new Node();
        JdbcTransactionCoordinator unavailable = new JdbcTransactionCoordinator(
                () -> { throw new SQLException("unavailable"); }, network, "checkpoint");
        try { unavailable.submit("op", "account", PAYLOAD, node); fail(); } catch (SQLException expected) { }
        assertEquals(0, node.composeCalls.get()); assertTrue(node.submitted.isEmpty());
    }

    @Test public void unsigned32BoundaryDoesNotWrap() throws Exception {
        Node node = new Node(); node.nodeIsn = 0xffff_ffffL;
        assertEquals("tx-4294967295", coordinator.submit("last", "account", PAYLOAD, node));
        try { coordinator.submit("overflow", "account", PAYLOAD, node); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("ISN")); }
    }

    @Test public void regressedNodeCounterRequiresReconciliation() throws Exception {
        Node node = new Node();
        coordinator.submit("first", "account", PAYLOAD, node);
        node.nodeIsn = 180;
        try { coordinator.submit("second", "account", PAYLOAD, node); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("regressed")); }
        assertEquals(1, node.composeCalls.get());
    }

    @Test public void jdbcDriverIsLoadedFromThePluginClassLoader() throws Exception {
        java.nio.file.Path password = java.nio.file.Files.createTempFile("isn-test-", ".password");
        try {
            Properties config = new Properties();
            config.setProperty("jdbcUrl", System.getProperty("isn.test.jdbc"));
            config.setProperty("user", "root"); config.setProperty("passwordFile", password.toString());
            config.setProperty("networkId", network); config.setProperty("checkpointHash", "checkpoint");
            java.util.concurrent.atomic.AtomicBoolean consulted = new java.util.concurrent.atomic.AtomicBoolean();
            ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals("com.mysql.cj.jdbc.Driver")) { consulted.set(true); }
                    return super.loadClass(name, resolve);
                }
            };
            assertEquals("tx-181", JdbcTransactionCoordinator.fromProperties(config, loader)
                    .submit("op", "account", PAYLOAD, new Node()));
            assertTrue(consulted.get());
        } finally { java.nio.file.Files.deleteIfExists(password); }
    }

    private void grantExpired(Node node) throws Exception {
        coordinator.submit("expired-intent", "account", PAYLOAD, node);
        byte[] old = new byte[16];
        old[2] = (byte) 0xe8; old[3] = 3; // timestamp 1000 ms, TTL one minute
        old[8] = (byte) 181;
        try (Connection c = connections.open()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE bridge_tx_submission SET signed_tx=? WHERE network_id=? AND operation_id='expired-intent'")) {
                s.setBytes(1, old); s.setString(2, network); s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("INSERT INTO bridge_tx_expired_slot(network_id,account,isn,expired_operation_id,witness_height,witness_hash) VALUES(?,'account',181,'expired-intent',10,'checkpoint')")) {
                s.setString(1, network); s.executeUpdate();
            }
        }
    }

    @Test public void expiredGrantPreservesHistoryAndHighWaterWhileServingConcurrentNewIntents() throws Exception {
        Node node = new Node(); grantExpired(node);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final String op = "new-" + i;
                futures.add(pool.submit(() -> coordinator.submit(op, "account", PAYLOAD, node)));
            }
            Set<String> values = new HashSet<>();
            for (Future<String> f : futures) { values.add(f.get(30, TimeUnit.SECONDS)); }
            assertEquals(16, values.size());
            assertTrue(values.contains("tx-181")); assertTrue(values.contains("tx-196"));
            assertEquals("tx-181", coordinator.submit("expired-intent", "account", PAYLOAD, node));
            try (Connection c = connections.open(); Statement s = c.createStatement()) {
                try (ResultSet r = s.executeQuery("SELECT next_isn FROM bridge_tx_account WHERE network_id='" + network + "'")) {
                    assertTrue(r.next()); assertEquals(197, r.getLong(1));
                }
                try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM bridge_tx_submission WHERE network_id='" + network + "'")) {
                    assertTrue(r.next()); assertEquals(17, r.getInt(1));
                }
            }
        } finally { pool.shutdownNow(); }
    }

    @Test public void stillValidWitnessAndSigningFailureDoNotConsumeGrant() throws Exception {
        Node node = new Node(); grantExpired(node);
        node.witnessTime = 61000;
        try { coordinator.submit("new", "account", PAYLOAD, node); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("expired")); }
        assertEquals(1, node.composeCalls.get());
        node.witnessTime = 70000; node.failSign = true;
        try { coordinator.submit("new", "account", PAYLOAD, node); fail(); } catch (Exception expected) { }
        node.failSign = false;
        assertEquals("tx-181", coordinator.submit("new", "account", PAYLOAD, node));
        assertEquals("tx-182", coordinator.submit("another", "account", PAYLOAD, node));
    }
}
