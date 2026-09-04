import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Client;
import com.alipay.mychain.sdk.crypto.hash.Hash;
import com.alipay.mychain.sdk.domain.account.Identity;
import com.alipay.mychain.sdk.vm.EVMParameter;
import org.slf4j.helpers.NOPLogger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Sends only to an existing dedicated test application; never deploys or changes Monitor control. */
public final class MychainBurstSend {
    public static void main(String[] args) throws Exception {
        if (args.length != 6 || !args[3].matches("[0-9a-fA-F]{64}")
                || !args[4].matches("[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("CONFIG TEST_SENDER DOMAIN TARGET_HEX UNIQUE_BATCH COUNT(1..32)");
        }
        int count = Integer.parseInt(args[5]);
        if (count < 1 || count > 32 || !args[1].startsWith("MonitorSender_")) {
            throw new IllegalArgumentException("requires dedicated MonitorSender test app and count 1..32");
        }
        Mychain020Client client = new Mychain020Client(Files.readAllBytes(Paths.get(args[0])), NOPLogger.NOP_LOGGER);
        if (!client.startup()) throw new IllegalStateException("SDK startup failed");
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SendResponseResult>> futures = new ArrayList<>();
        long begin = System.nanoTime();
        try {
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    EVMParameter p = new EVMParameter("sendUnordered(identity,string,bytes)");
                    p.addIdentity(new Identity(new Hash(args[3])));
                    p.addString(args[2]);
                    p.addBytes((args[4] + "-" + index).getBytes(StandardCharsets.UTF_8));
                    return client.callContract(args[1], p, true);
                }));
            }
            start.countDown();
            for (int i = 0; i < count; i++) {
                SendResponseResult r = futures.get(i).get(90, TimeUnit.SECONDS);
                System.out.println("{\"batch\":\"" + args[4] + "\",\"index\":" + i
                        + ",\"txHash\":\"" + r.getTxId() + "\",\"confirmed\":" + r.isConfirmed()
                        + ",\"success\":" + r.isSuccess() + "}");
                if (!r.isSuccess() || !r.isConfirmed()) throw new IllegalStateException("source execution failed at " + i);
            }
            System.out.println("{\"count\":" + count + ",\"burstElapsedMs\":"
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) + "}");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(95, TimeUnit.SECONDS);
            client.shutdown();
        }
    }
}
