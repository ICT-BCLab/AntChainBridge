package com.alipay.antchain.bridge.plugins.fiscobcos3;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import com.alipay.antchain.bridge.plugins.fiscobcos3.demo.contract.MonitorSenderContract;
import com.alipay.antchain.bridge.plugins.fiscobcos3.demo.contract.ReceiverContract;
import org.fisco.bcos.sdk.model.TransactionReceipt;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class FISCOBCOSMonitorDemoFlowTest {

    private FISCOBCOSBBCService service;

    @Before
    public void setUp() throws Exception {
        String configPath = System.getProperty("fisco.demo.config");
        Assume.assumeTrue(configPath != null && !configPath.trim().isEmpty());

        JSONObject config = JSON.parseObject(
                new String(Files.readAllBytes(Paths.get(configPath)), StandardCharsets.UTF_8));
        config.remove("accountAddress");
        DefaultBBCContext context = new DefaultBBCContext();
        context.setConfForBlockchainClient(config.toJSONString().getBytes(StandardCharsets.UTF_8));
        service = new FISCOBCOSBBCService();
        service.startup(context);
    }

    @After
    public void tearDown() {
        if (service != null && service.getClient() != null) {
            service.shutdown();
        }
    }

    @Test
    public void test01DeployDemoContracts() throws Exception {
        String monitorAddress = requiredProperty("fisco.demo.monitor");
        MonitorSenderContract sender = MonitorSenderContract.deploy(service.getClient(), service.getKeyPair());
        ReceiverContract receiver = ReceiverContract.deploy(service.getClient(), service.getKeyPair());
        TransactionReceipt receipt = sender.setMonitorAddress(monitorAddress);
        Assert.assertTrue(receipt.isStatusOK());

        Map<String, String> result = new LinkedHashMap<>();
        result.put("senderContract", sender.getContractAddress());
        result.put("senderIdentity", addressToIdentity(sender.getContractAddress()));
        result.put("receiverContract", receiver.getContractAddress());
        result.put("receiverIdentity", addressToIdentity(receiver.getContractAddress()));
        result.put("monitorContract", monitorAddress);
        result.put("setMonitorTx", receipt.getTransactionHash());
        System.out.println("FISCO_MONITOR_DEMO=" + JSON.toJSONString(result));
    }

    @Test
    public void test02SendMonitoredMessage() {
        TransactionReceipt receipt = sendMessage();
        Assert.assertTrue("monitored send failed: " + receipt.getMessage(), receipt.isStatusOK());
        System.out.println("FISCO_MONITORED_SEND_TX=" + receipt.getTransactionHash());
    }

    @Test
    public void test03QueryReceiver() throws Exception {
        ReceiverContract receiver = ReceiverContract.load(
                requiredProperty("fisco.demo.receiver"), service.getClient(), service.getKeyPair());
        String expected = System.getProperty("fisco.demo.message", "fisco-monitor-demo");
        String ordered = new String(receiver.getLastMsg(), StandardCharsets.UTF_8);
        String unordered = new String(receiver.getLastUnorderedMsg(), StandardCharsets.UTF_8);
        Assert.assertEquals("business contract received monitor envelope or padding", expected, ordered);
        System.out.println("FISCO_LAST_ORDERED_MSG=" + ordered);
        System.out.println("FISCO_LAST_UNORDERED_MSG=" + unordered);
    }

    @Test
    public void test04ExpectBlockedSend() {
        TransactionReceipt receipt = sendMessage();
        Assert.assertFalse("blacklisted sender unexpectedly sent a message", receipt.isStatusOK());
        System.out.println("FISCO_BLOCKED_SEND_TX=" + receipt.getTransactionHash());
        System.out.println("FISCO_BLOCKED_SEND_STATUS=" + receipt.getStatus());
        System.out.println("FISCO_BLOCKED_SEND_MESSAGE=" + receipt.getMessage());
    }

    private TransactionReceipt sendMessage() {
        MonitorSenderContract sender = MonitorSenderContract.load(
                requiredProperty("fisco.demo.sender"), service.getClient(), service.getKeyPair());
        byte[] receiver = decodeIdentity(requiredProperty("fisco.demo.receiverIdentity"));
        String receiverDomain = requiredProperty("fisco.demo.receiverDomain");
        String message = System.getProperty("fisco.demo.message", "fisco-monitor-demo");
        return sender.sendMonitored(receiver, receiverDomain, message.getBytes(StandardCharsets.UTF_8));
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("missing system property -D" + name);
        }
        return value;
    }

    private static String addressToIdentity(String address) {
        String normalized = stripHexPrefix(address);
        return String.format("%64s", normalized).replace(' ', '0').toLowerCase();
    }

    private static byte[] decodeIdentity(String identity) {
        String normalized = stripHexPrefix(identity);
        if (!normalized.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("identity must contain 32 bytes of hex: " + identity);
        }
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(normalized.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }

    private static String stripHexPrefix(String value) {
        return value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
    }
}
