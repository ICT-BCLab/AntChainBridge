import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import com.alibaba.fastjson.JSON;
import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Client;
import com.alipay.mychain.sdk.common.VMTypeEnum;
import com.alipay.mychain.sdk.domain.account.Identity;
import com.alipay.mychain.sdk.message.transaction.contract.CallContractRequest;
import com.alipay.mychain.sdk.message.query.QueryTransactionReceiptResponse;
import com.alipay.mychain.sdk.vm.EVMParameter;
import org.slf4j.helpers.NOPLogger;

/** Instrument an isolated client, preserving the production SDK's synchronous send path. */
public final class MychainLatencySender {
    private static PrintStream output;
    private static Path attempt;
    private static long started;

    private static String hash(String raw) {
        String value = raw.replaceFirst("^0[xX]", "").toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}") || value.matches("0{64}")) throw new IllegalArgumentException("invalid identity/hash");
        return value;
    }

    private static void event(String phase, Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        long wall = System.currentTimeMillis();
        value.put("phase", phase);
        value.put("epochMs", wall);
        value.put("atTokyo", Instant.ofEpochMilli(wall).atOffset(ZoneOffset.ofHours(9)).toString());
        value.put("elapsedMs", (System.nanoTime() - started) / 1_000_000.0);
        for (int i = 0; i < pairs.length; i += 2) value.put((String)pairs[i], pairs[i+1]);
        String line = JSON.toJSONString(value);
        if (attempt != null) {
            try (FileChannel file = FileChannel.open(attempt, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer bytes = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            } catch (Exception failure) { throw new IllegalStateException("cannot persist measurement", failure); }
        }
        output.println("PUBLIC_EVENT=" + line);
        output.flush();
    }

    private static final class TimedClient extends Mychain020Client {
        String preparedHash;
        TimedClient(byte[] config) { super(config, NOPLogger.NOP_LOGGER); }
        @Override public SendResponseResult sendCallContractRequest(CallContractRequest request, boolean sync) {
            preparedHash = hash(request.getTransaction().getHash().hexStrValue());
            event("HASH_GENERATED", "txHash", preparedHash);
            event("BROADCAST_BEGIN");
            SendResponseResult result = super.sendCallContractRequest(request, sync);
            if (result == null) throw new IllegalStateException("source outcome unknown");
            event("SOURCE_SYNC_RETURN", "txHash", result.getTxId(), "confirmed", result.isConfirmed(),
                    "success", result.isSuccess(), "errorCode", result.getErrorCode());
            if (!preparedHash.equals(hash(result.getTxId()))) throw new IllegalStateException("SDK hash changed; reconcile original attempt");
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("CONFIG SOURCE_ID TARGET_DOMAIN TARGET_ID MESSAGE CHECK_ONLY|SEND_ONCE ATTEMPT_FILE");
        output = System.out;
        PrintStream discard = new PrintStream(new OutputStream() { public void write(int value) { } });
        // SDK diagnostics can contain transactions. Export only explicit PUBLIC_EVENT metadata.
        System.setOut(discard); System.setErr(discard);
        started = System.nanoTime();
        boolean send = "SEND_ONCE".equals(args[5]);
        if (!send && !"CHECK_ONLY".equals(args[5])) throw new IllegalArgumentException("invalid mode");
        String source = hash(args[1]), target = hash(args[3]);
        if (args[2].trim().isEmpty() || args[4].isEmpty()) throw new IllegalArgumentException("empty domain/message");
        if (send) {
            Path unique = Paths.get(args[6]);
            Files.createFile(unique, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            attempt = unique;
        }
        event("PROCESS_START", "mode", args[5]);
        TimedClient client = new TimedClient(Files.readAllBytes(Paths.get(args[0])));
        boolean passed = false;
        try {
            event("SDK_CONNECT_BEGIN");
            if (!client.startup()) throw new IllegalStateException("SDK startup failed");
            event("SDK_READY");
            VMTypeEnum type = client.getContractType(source);
            if (type != VMTypeEnum.EVM) throw new IllegalStateException("source is not EVM");
            event("SOURCE_CONTRACT_OK", "sourceIdentity", source, "vm", type.name());
            if (!send) { passed = true; return; }
            EVMParameter parameters = new EVMParameter("sendUnordered(identity,string,bytes)");
            parameters.addIdentity(new Identity(target));
            parameters.addString(args[2]);
            parameters.addBytes(args[4].getBytes(StandardCharsets.UTF_8));
            event("SOURCE_CALL_BEGIN");
            SendResponseResult result = client.callContract(new Identity(source), parameters, true);
            if (!result.isConfirmed() || !result.isSuccess()) throw new IllegalStateException("source not successful; do not resend");
            QueryTransactionReceiptResponse receipt = client.getTxReceiptByTxhash(result.getTxId());
            if (receipt == null || !receipt.isSuccess() || receipt.getBlockNumber().signum() <= 0
                    || receipt.getTransactionReceipt() == null || receipt.getTransactionReceipt().getResult() != 0) {
                throw new IllegalStateException("source receipt unavailable; do not resend");
            }
            event("SOURCE_RECEIPT_VERIFIED", "blockHeight", receipt.getBlockNumber().toString(), "result", 0);
            event("SOURCE_BLOCK_HEADER", "blockTimestampRaw", client.getBlockByHeight(receipt.getBlockNumber().longValue()).getBlockHeader().getTimestamp());
            passed = true;
        } catch (Exception failure) {
            event("SOURCE_ERROR", "errorClass", failure.getClass().getSimpleName());
        } finally {
            client.shutdown(); event("PROCESS_DONE", "passed", passed);
        }
        if (!passed) System.exit(1);
    }
}
