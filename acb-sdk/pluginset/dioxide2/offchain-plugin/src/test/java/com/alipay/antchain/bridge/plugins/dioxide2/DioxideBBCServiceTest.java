package com.alipay.antchain.bridge.plugins.dioxide2;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alipay.antchain.bridge.commons.bbc.AbstractBBCContext;
import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import com.alipay.antchain.bridge.commons.bbc.syscontract.AuthMessageContract;
import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.commons.bbc.syscontract.MonitorContract;
import com.alipay.antchain.bridge.commons.bbc.syscontract.SDPContract;
import com.alipay.antchain.bridge.commons.core.am.AuthMessageFactory;
import com.alipay.antchain.bridge.commons.core.am.IAuthMessage;
import com.alipay.antchain.bridge.commons.core.base.CrossChainMessage;
import com.alipay.antchain.bridge.commons.core.base.CrossChainMessageReceipt;
import com.alipay.antchain.bridge.commons.core.sdp.ISDPMessage;
import com.alipay.antchain.bridge.commons.core.sdp.SDPMessageFactory;
import com.alipay.antchain.bridge.commons.core.monitor.IMonitorMessage;
import com.alipay.antchain.bridge.commons.core.monitor.MonitorMessageFactory;

import com.alipay.antchain.bridge.commons.core.ptc.ThirdPartyBlockchainTrustAnchorV1;
import com.alipay.antchain.bridge.commons.core.base.CrossChainLane;
import com.alipay.antchain.bridge.commons.core.base.CrossChainDomain;

import com.alipay.antchain.bridge.commons.utils.codec.tlv.TLVTypeEnum;
import com.alipay.antchain.bridge.commons.utils.codec.tlv.TLVUtils;
import com.alipay.antchain.bridge.commons.utils.codec.tlv.annotation.TLVField;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideConfig;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideTypes;
import com.alipay.antchain.bridge.plugins.dioxide2.core.*;
import com.alipay.antchain.bridge.plugins.dioxide2.gcl.Contracts;
import com.alipay.antchain.bridge.plugins.spi.bbc.AbstractBBCService;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;

import java.beans.Transient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
public class DioxideBBCServiceTest {

    private static final String RPC_URL = "http://127.0.0.1:62222/api";

    private static final String INVALID_RPC_URL = "http://invalid_rpc_url/api";

    private static final String WS_RPC = "ws://127.0.0.1:62222/api";

    private static final String DAPP_NAME = "AcbDapp";

    private static final String BBC_DIO_PRIVATE_KEY = "WTKi+W99TEEt153Zt8isUznwXqYkA0aVWEbd7edk6AvivGov5hBLJLQbS2hk8bnC3FM8Et6+Axaw1uukce+ZEQ==";

    private static final String CHAIN_DOMAIN = "123456";

    private static final String AM_CONTRACT = "AuthMsg";

    private static final String SDP_CONTRACT = "SDPMsg";

    private static final String APP_CONTRACT = "AppContract";

    private static final String TEST_MESSAGE = "awesome antchain-bridge";

    private static boolean setupBBC;

    private static DioxideBBCService dioxideBBCService;

    private static String random_dapp_name;

    private static long dappCid = 12341234;

    @BeforeClass
    public static void init() throws Exception {

        dioxideBBCService = new DioxideBBCService();
        Method method = AbstractBBCService.class.getDeclaredMethod("setLogger", Logger.class);
        method.setAccessible(true);
        method.invoke(dioxideBBCService, log);

        random_dapp_name = ranDomDappId();
        log.info("random_dapp_name: {}", random_dapp_name);

        System.out.println(dioxideBBCService.getBBCLogger().getClass());
    }

    public static String ranDomDappId() {
        final String prefix = "Dapp";
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        final int base = chars.length();

        java.security.SecureRandom random = new java.security.SecureRandom();

        // 后缀长度：1~4
        int suffixLen = 1 + random.nextInt(4);

        // 秒级时间因子（防同一秒大量重复）
        long timeFactor = (System.currentTimeMillis() / 1000) % base;

        StringBuilder suffix = new StringBuilder(suffixLen);

        // 第一位混入时间（弱相关）
        suffix.append(chars.charAt((int) timeFactor));

        // 剩余位全部真随机
        while (suffix.length() < suffixLen) {
            suffix.append(chars.charAt(random.nextInt(base)));
        }

        return prefix + suffix.toString();
    }

    @Test
    public void testStartup() {
        // start up success
        AbstractBBCContext mockValidCtx = mockValidCtx();
        DioxideBBCService dioxideBBCService = new DioxideBBCService();
        dioxideBBCService.startup(mockValidCtx);
        Assert.assertNull(dioxideBBCService.getBbcContext().getAuthMessageContract());
        Assert.assertNull(dioxideBBCService.getBbcContext().getSdpContract());

        // start up failed
        AbstractBBCContext mockInvalidCtx = mockInvalidCtx();
        try {
            dioxideBBCService.startup(mockInvalidCtx);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testStartupWithDeployedContract() {
        // start up a tmp
        AbstractBBCContext mockValidCtx = mockValidCtx();
        DioxideBBCService bbcServiceTmp = new DioxideBBCService();
        bbcServiceTmp.startup(mockValidCtx);

        // set up am and sdp and monitor
        bbcServiceTmp.setupAuthMessageContract();
        bbcServiceTmp.setupSDPMessageContract();
        bbcServiceTmp.setupMonitorContract();
        String amAddr = bbcServiceTmp.getContext().getAuthMessageContract().getContractAddress();
        String sdpAddr = bbcServiceTmp.getContext().getSdpContract().getContractAddress();
        String monitorAddr = bbcServiceTmp.getContext().getMonitorContract().getContractAddress();

        // start up success
        AbstractBBCContext ctx = mockValidCtxWithPreDeployedContracts(amAddr, sdpAddr, monitorAddr);
        DioxideBBCService dioxideBBCService = new DioxideBBCService();
        dioxideBBCService.startup(ctx);

        Assert.assertEquals(amAddr, dioxideBBCService.getBbcContext().getAuthMessageContract().getContractAddress());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, dioxideBBCService.getBbcContext().getAuthMessageContract().getStatus());
        Assert.assertEquals(sdpAddr, dioxideBBCService.getBbcContext().getSdpContract().getContractAddress());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, dioxideBBCService.getBbcContext().getSdpContract().getStatus());
        Assert.assertEquals(monitorAddr, dioxideBBCService.getBbcContext().getMonitorContract().getContractAddress());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, dioxideBBCService.getBbcContext().getMonitorContract().getStatus());
    }

    @Test
    public void testStartupWithReadyContract() {
        // start up a tmp
        AbstractBBCContext mockValidCtx = mockValidCtx();
        DioxideBBCService bbcServiceTmp = new DioxideBBCService();
        bbcServiceTmp.startup(mockValidCtx);

        // set up am and sdp and monitor
        bbcServiceTmp.setupAuthMessageContract();
        bbcServiceTmp.setupSDPMessageContract();
        bbcServiceTmp.setupMonitorContract();
        String amAddr = bbcServiceTmp.getContext().getAuthMessageContract().getContractAddress();
        String sdpAddr = bbcServiceTmp.getContext().getSdpContract().getContractAddress();
        String monitorAddr = bbcServiceTmp.getContext().getMonitorContract().getContractAddress();

        // start up success
        DioxideBBCService dioxideBBCService = new DioxideBBCService();
        AbstractBBCContext ctx = mockValidCtxWithPreReadyContracts(amAddr, sdpAddr, monitorAddr);
        dioxideBBCService.startup(ctx);
        Assert.assertEquals(amAddr, dioxideBBCService.getBbcContext().getAuthMessageContract().getContractAddress());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, dioxideBBCService.getBbcContext().getAuthMessageContract().getStatus());
        Assert.assertEquals(sdpAddr, dioxideBBCService.getBbcContext().getSdpContract().getContractAddress());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, dioxideBBCService.getBbcContext().getSdpContract().getStatus());
        Assert.assertEquals(monitorAddr, dioxideBBCService.getBbcContext().getMonitorContract().getContractAddress());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, dioxideBBCService.getBbcContext().getMonitorContract().getStatus());
    }

    @Test
    public void testShutdown() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        DioxideBBCService dioxideBBCService = new DioxideBBCService();
        dioxideBBCService.startup(mockValidCtx);
        dioxideBBCService.shutdown();
    }

    @Test
    public void testGetContext() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        DioxideBBCService dioxideBBCService = new DioxideBBCService();
        dioxideBBCService.startup(mockValidCtx);
        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertNotNull(ctx);
        Assert.assertNull(ctx.getAuthMessageContract());
    }

    @Test
    public void testQueryLatestHeight() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        Long height = dioxideBBCService.queryLatestHeight();
        log.info("height: {}", height);
    }

    @Test
    public void testSetupAuthMessageContract() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        dioxideBBCService.setupAuthMessageContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getAuthMessageContract().getStatus());
        log.info("am contract cid: {}", ctx.getAuthMessageContract().getContractAddress());
    }


    @Test
    public void testSetupSDPMessageContract() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        dioxideBBCService.setupSDPMessageContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());
        log.info("sdp contract cid: {}", ctx.getSdpContract().getContractAddress());
    }

    @Test
    public void testSetupMonitorContract() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        dioxideBBCService.setupMonitorContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getMonitorContract().getStatus());
        log.info("monitor contract cid: {}", ctx.getMonitorContract().getContractAddress());
    }

    @Test
    public void testQuerySDPMessageSeq() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        // set up sdp
        dioxideBBCService.setupSDPMessageContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());
        log.info("sdp contract cid: {}", ctx.getSdpContract().getContractAddress());

        long seq = dioxideBBCService.querySDPMessageSeq(
                "senderDomain",
                DigestUtil.sha256Hex("senderID"),
                CHAIN_DOMAIN,
                DigestUtil.sha256Hex("receiverID")
        );
        Assert.assertEquals(0L, seq);
    }

    @Test
    public void testSetAmContractAndLocalDomainAndMonitorContract() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        // set up am and sdp
        dioxideBBCService.setupAuthMessageContract();
        dioxideBBCService.setupSDPMessageContract();
        dioxideBBCService.setupMonitorContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getAuthMessageContract().getStatus());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getMonitorContract().getStatus());
        log.info("am contract cid: {}", ctx.getAuthMessageContract().getContractAddress());
        log.info("sdp contract cid: {}", ctx.getSdpContract().getContractAddress());
        log.info("monitor contract cid: {}", ctx.getMonitorContract().getContractAddress());

        // set am to sdp
        dioxideBBCService.setAmContract(ctx.getAuthMessageContract().getContractAddress());

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());

        // set domain to sdp
        dioxideBBCService.setLocalDomain(CHAIN_DOMAIN);

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());

        // set monitor to sdp
        dioxideBBCService.setMonitorContract(ctx.getMonitorContract().getContractAddress());

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, ctx.getSdpContract().getStatus());
    }

    @Test
    public void testSetProtocolToMonitor() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        // set up monitor and sdp
        dioxideBBCService.setupSDPMessageContract();
        dioxideBBCService.setupMonitorContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getMonitorContract().getStatus());
        log.info("sdp contract cid: {}", ctx.getSdpContract().getContractAddress());
        log.info("monitor contract cid: {}", ctx.getMonitorContract().getContractAddress());

        // set protocol to monitor
        dioxideBBCService.setProtocolInMonitor(ctx.getSdpContract().getContractAddress());

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, ctx.getMonitorContract().getStatus());
    }

    @Test
    public void testSetProtocol() {
        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        // set up am and sdp
        dioxideBBCService.setupAuthMessageContract();
        dioxideBBCService.setupSDPMessageContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getAuthMessageContract().getStatus());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());
        log.info("am contract cid: {}", ctx.getAuthMessageContract().getContractAddress());
        log.info("sdp contract cid: {}", ctx.getSdpContract().getContractAddress());

        // set protocol to am
        dioxideBBCService.setProtocol(ctx.getSdpContract().getContractAddress(), "0");

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, ctx.getAuthMessageContract().getStatus());
    }

    @Test
    public void testReadCrossChainMessagesByHeight_sendUnordered() {
        setupBbc();

        // app send msg
        int[] receiverDomainArray = toIntArray("112233".getBytes(StandardCharsets.UTF_8));
        int[] receiverIDArray = toIntArray(HexUtil.decodeHex(DigestUtil.sha256Hex("receiverID")));
        int[] msgArray = toIntArray("abcd".getBytes(StandardCharsets.UTF_8));
        String txHash = dioxideBBCService.getDioxideClient().sendTransaction(
                JSON.toJSONString(Map.of(
                        "sender", dioxideBBCService.getDioxideClient().getDioxideAccount().getAddressInString(),
                        "function", String.format("%s.%s.sendUnorderedMessage", random_dapp_name, APP_CONTRACT),
                        "args", Map.of(
                                "receiverDomain", receiverDomainArray,
                                "receiver", receiverIDArray,
                                "message", msgArray
                        )
                )),
                true
        );

        // test relay tx info
        DioxideTransaction dtx = dioxideBBCService.getDioxideClient().getTransactionByHash(txHash);
        log.info("[sendUnorderedMessage] tx info: \n{}", JSON.toJSONString(dtx, SerializerFeature.PrettyFormat));

        List<String> relayTx = dtx.getInvocation().getRelays();
        if (CollUtil.isNotEmpty(relayTx)) {
            log.info("relay tx: {}", relayTx);
            relayTx.forEach(tx -> {
                String realTx = tx.split(":")[0];
                DioxideTransaction dtx2 = dioxideBBCService.getDioxideClient().getTransactionByHash(realTx);
                log.info("relayTx info-{}: \n{}", realTx, JSON.toJSONString(dtx2, SerializerFeature.PrettyFormat));
            });
        }
        // and get relay tx by block height
        long height = dtx.getHeight();
        DioxideTransactionBlock block = dioxideBBCService.getDioxideClient().getTransactionBlockInGlobalShardByHeight(height);
        log.info("block-info-height-{}: \n{}", height, JSON.toJSONString(block, SerializerFeature.PrettyFormat));

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态，这在 Java 多线程中是最佳实践
        }
        List<CrossChainMessage> messageList = ListUtil.toList();
        for (long i = height-5; i < height+5; i++) {
            messageList.addAll(dioxideBBCService.readCrossChainMessagesByHeight(i));
        }
        Assert.assertEquals(1, messageList.size());
        Assert.assertEquals(CrossChainMessage.CrossChainMessageType.AUTH_MSG, messageList.getFirst().getType());

        log.info("messageList:");
        messageList.forEach(message -> {
           log.info("\n{}", JSON.toJSONString(message, SerializerFeature.PrettyFormat));
        });

        log.info("parse am event:\n{}", toIntArray(messageList.get(0).getMessage()));
    }

    @Test
    public void testReadCrossChainMessagesByHeight_sendOrdered() {
        setupBbc();

        // app send msg
        int[] receiverDomainArray = toIntArray("112233".getBytes(StandardCharsets.UTF_8));
        int[] receiverIDArray = toIntArray(HexUtil.decodeHex(DigestUtil.sha256Hex("receiverID")));
        int[] msgArray = toIntArray("abcd".getBytes(StandardCharsets.UTF_8));
        String txHash = dioxideBBCService.getDioxideClient().sendTransaction(
                JSON.toJSONString(Map.of(
                        "sender", dioxideBBCService.getDioxideClient().getDioxideAccount().getAddressInString(),
                        "function", String.format("%s.%s.sendMessage", random_dapp_name, APP_CONTRACT),
                        "args", Map.of(
                                "receiverDomain", receiverDomainArray,
                                "receiver", receiverIDArray,
                                "message", msgArray
                        )
                )),
                true
        );

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 恢复中断状态，这在 Java 多线程中是最佳实践
        }
        long height = dioxideBBCService.getDioxideClient().getTransactionByHash(txHash).getHeight();
        List<CrossChainMessage> messageList = ListUtil.toList();
        for (long i = height-5; i < height+10; i++) {
            messageList.addAll(dioxideBBCService.readCrossChainMessagesByHeight(i));
        }
        Assert.assertEquals(1, messageList.size());
        Assert.assertEquals(CrossChainMessage.CrossChainMessageType.AUTH_MSG, messageList.getFirst().getType());

        DioxideTransaction dtx = dioxideBBCService.getDioxideClient().getTransactionByHash(txHash);
        log.info("[sendMessage] tx info:\n{}", JSON.toJSONString(dtx, SerializerFeature.PrettyFormat));

        log.info("messageList:");
        messageList.forEach(message -> {
            log.info("\n{}", JSON.toJSONString(message, SerializerFeature.PrettyFormat));
        });
    }

    @Test
    public void testRelayAuthMessageAndReadCrossChainMessageReceipt() throws Exception {
        setupBbc();

        // relay am msg
        CrossChainMessageReceipt receipt = dioxideBBCService.relayAuthMessage(getRawMsgFromRelayer());
        Assert.assertFalse(receipt.isConfirmed());
        Assert.assertTrue(receipt.isSuccessful());
        Assert.assertTrue(StrUtil.isNotEmpty(receipt.getTxhash()));

        // wait for the entire relay chain to be finalized
        while (true) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            CrossChainMessageReceipt receipt1 = dioxideBBCService.readCrossChainMessageReceipt(receipt.getTxhash());
            if (receipt1.isConfirmed() && receipt1.isSuccessful()) {
                break;
            }
        }

        // check variable status in dapp
        JSONObject resp3 = dioxideBBCService.getDioxideClient().getContractState(dioxideBBCService.getConfig().getDappName(), APP_CONTRACT, DioxideTypes.Scope.Global, "");
        int[] receiveUnorderedMsg = resp3.getJSONObject("State").getJSONArray("last_uo_msg").toJavaObject(int[].class);
        log.info("receiveUnorderedMsg:\n{}", receiveUnorderedMsg);
        Assert.assertArrayEquals(receiveUnorderedMsg, toIntArray(TEST_MESSAGE.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testGenerateHexRawMsg() throws Exception {
        byte[] rawMsg = getRawMsgFromRelayer();
        String hexString = HexUtil.encodeHexStr(rawMsg);
        System.out.println(hexString);
    }

    @SneakyThrows
    private void setupBbc() {
        if (setupBBC) {
            return;
        }

        AbstractBBCContext mockValidCtx = mockValidCtx();
        dioxideBBCService.startup(mockValidCtx);

        // set up am and sdp and monitor
        dioxideBBCService.setupAuthMessageContract();
        dioxideBBCService.setupSDPMessageContract();
        dioxideBBCService.setupMonitorContract();

        AbstractBBCContext ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getAuthMessageContract().getStatus());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getMonitorContract().getStatus());
        log.info("am contract cid: {}", ctx.getAuthMessageContract().getContractAddress());
        log.info("sdp contract cid: {}", ctx.getSdpContract().getContractAddress());
        log.info("monitor contract cid: {}", ctx.getMonitorContract().getContractAddress());

        // set am to sdp
        dioxideBBCService.setAmContract(ctx.getAuthMessageContract().getContractAddress());

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());

        // set domain to sdp
        dioxideBBCService.setLocalDomain(CHAIN_DOMAIN);

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_DEPLOYED, ctx.getSdpContract().getStatus());

        // set monitor to sdp
        dioxideBBCService.setMonitorContract(ctx.getMonitorContract().getContractAddress());

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, ctx.getSdpContract().getStatus());

        // set protocol to am
        dioxideBBCService.setProtocol(ctx.getSdpContract().getContractAddress(), "0");

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, ctx.getAuthMessageContract().getStatus());

        // set protocol to monitor
        dioxideBBCService.setProtocolInMonitor(ctx.getSdpContract().getContractAddress());

        // check contract status
        ctx = dioxideBBCService.getContext();
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, ctx.getMonitorContract().getStatus());

        // set up app
        String dappContractSource = new String(
                Base64.getDecoder().decode(Contracts.APPCONTRACT),
                StandardCharsets.UTF_8
        );
        dappCid = dioxideBBCService.getDioxideClient().deployContract(APP_CONTRACT, dappContractSource, JSON.toJSONString(Map.of(
                "_owner", dioxideBBCService.getDioxideClient().getDioxideAccount().getAddressInString()
        )));
        log.info("setup dapp, cid: {}", dappCid);

        // set monitor to dapp
        long monitorCid = Long.parseLong(ctx.getMonitorContract().getContractAddress());
        String monitorAddress = String.format("0x%016X:contract", monitorCid);
        dioxideBBCService.getDioxideClient().sendTransaction(
                JSON.toJSONString(Map.of(
                        "sender", dioxideBBCService.getDioxideClient().getDioxideAccount().getAddressInString(),
                        "function", String.format("%s.%s.setMonitor", random_dapp_name, APP_CONTRACT),
                        "args", Map.of(
                                "_monitorContractId", monitorCid,
                                "_monitorAddress", monitorAddress
                        )
                )),
                true
        );
        log.info("set monitor to app contract");

        setupBBC = true;
    }

    private int[] toIntArray(byte[] bytes) {
        int[] arr = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            arr[i] = bytes[i] & 0xFF;  // 转无符号 uint8
        }
        return arr;
    }

    private AbstractBBCContext mockValidCtx() {
        DioxideConfig mockConf = new DioxideConfig();
        mockConf.setRpcUrl(RPC_URL);
        mockConf.setWsRpc(WS_RPC);
        mockConf.setPrivateKey(BBC_DIO_PRIVATE_KEY);
        mockConf.setDappName(random_dapp_name);
        mockConf.setIsPreContractDeployed(false);

        AbstractBBCContext mockCtx = new DefaultBBCContext();
        mockCtx.setConfForBlockchainClient(mockConf.toJsonString().getBytes(StandardCharsets.UTF_8));
        return mockCtx;
    }

    private AbstractBBCContext mockValidCtxWithPreDeployedContracts(String amAddr, String sdpAddr, String monitorAddr) {
        DioxideConfig mockConf = new DioxideConfig();
        mockConf.setRpcUrl(RPC_URL);
        mockConf.setWsRpc(WS_RPC);
        mockConf.setPrivateKey(BBC_DIO_PRIVATE_KEY);
        mockConf.setDappName(DAPP_NAME);
        mockConf.setIsPreContractDeployed(true);

        mockConf.setAmContractAddressDeployed(amAddr);
        mockConf.setSdpContractAddressDeployed(sdpAddr);
        mockConf.setMonitorContractAddressDeployed(monitorAddr);

        AbstractBBCContext mockCtx = new DefaultBBCContext();
        mockCtx.setConfForBlockchainClient(mockConf.toJsonString().getBytes());

        return mockCtx;
    }

    private AbstractBBCContext mockValidCtxWithPreReadyContracts(String amAddr, String sdpAddr, String monitorAddr) {
        DioxideConfig mockConf = new DioxideConfig();
        mockConf.setRpcUrl(RPC_URL);
        mockConf.setWsRpc(WS_RPC);
        mockConf.setPrivateKey(BBC_DIO_PRIVATE_KEY);
        mockConf.setDappName(DAPP_NAME);
        mockConf.setIsPreContractDeployed(true);

        mockConf.setAmContractAddressDeployed(amAddr);
        mockConf.setSdpContractAddressDeployed(sdpAddr);
        mockConf.setMonitorContractAddressDeployed(monitorAddr);

        AbstractBBCContext mockCtx = new DefaultBBCContext();
        mockCtx.setConfForBlockchainClient(mockConf.toJsonString().getBytes());

        AuthMessageContract authMessageContract = new AuthMessageContract();
        authMessageContract.setContractAddress(mockConf.getAmContractAddressDeployed());
        authMessageContract.setStatus(ContractStatusEnum.CONTRACT_READY);
        mockCtx.setAuthMessageContract(authMessageContract);
        SDPContract sdpContract = new SDPContract();
        sdpContract.setContractAddress(mockConf.getSdpContractAddressDeployed());
        sdpContract.setStatus(ContractStatusEnum.CONTRACT_READY);
        mockCtx.setSdpContract(sdpContract);
        MonitorContract monitorContract = new MonitorContract();
        monitorContract.setContractAddress(mockConf.getMonitorContractAddressDeployed());
        monitorContract.setStatus(ContractStatusEnum.CONTRACT_READY);
        mockCtx.setMonitorContract(monitorContract);

        return mockCtx;
    }

    private AbstractBBCContext mockInvalidCtx() {
        DioxideConfig mockConf = new DioxideConfig();
        mockConf.setRpcUrl(INVALID_RPC_URL);
        mockConf.setWsRpc(WS_RPC);
        mockConf.setPrivateKey(BBC_DIO_PRIVATE_KEY);
        mockConf.setDappName(random_dapp_name);
        mockConf.setIsPreContractDeployed(false);

        AbstractBBCContext mockCtx = new DefaultBBCContext();
        mockCtx.setConfForBlockchainClient(mockConf.toJsonString().getBytes(StandardCharsets.UTF_8));
        return mockCtx;
    }

    private byte[] getRawMsgFromRelayer() throws IOException {
        String dappCidHex = String.format("%064x", dappCid);

        IMonitorMessage monitorMessage = MonitorMessageFactory.createMonitorMessage(
                1,
                1,
                "667788",
                TEST_MESSAGE.getBytes(StandardCharsets.UTF_8)
        );
        
        ISDPMessage sdpMessage = SDPMessageFactory.createSDPMessage(
                1,
                new byte[32],
                CHAIN_DOMAIN,
                HexUtil.decodeHex(dappCidHex),
                -1,
                monitorMessage.encode()
        );

        IAuthMessage am = AuthMessageFactory.createAuthMessage(
                1,
                HexUtil.decodeHex(
                        String.format("000000000000000000000000%s", StrUtil.removePrefix("0x0000000000000000000000000000001111111111", "0x"))
                ),
                0,
                sdpMessage.encode()
        );
        MockResp resp = new MockResp();
        resp.setRawResponse(am.encode());

        MockProof proof = new MockProof();
        proof.setResp(resp);
        proof.setDomain("112233");

        byte[] rawProof = TLVUtils.encode(proof);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write(new byte[]{0, 0, 0, 0});

        int len = rawProof.length;
        stream.write((len >>> 24) & 0xFF);
        stream.write((len >>> 16) & 0xFF);
        stream.write((len >>> 8) & 0xFF);
        stream.write((len) & 0xFF);

        stream.write(rawProof);

        return stream.toByteArray();
    }


    @Test
    public void testTba() throws IOException {
        ThirdPartyBlockchainTrustAnchorV1 tpBtaOnlyRepresentDioxide = new ThirdPartyBlockchainTrustAnchorV1();
        tpBtaOnlyRepresentDioxide.setCrossChainLane(new CrossChainLane(new CrossChainDomain("dioxide")));
        System.out.println(tpBtaOnlyRepresentDioxide.getCrossChainLane().getSenderDomain().getDomain());
    }


    @Getter
    @Setter
    public static class MockProof {

        @TLVField(tag = 5, type = TLVTypeEnum.BYTES)
        private MockResp resp;

        @TLVField(tag = 9, type = TLVTypeEnum.STRING)
        private String domain;
    }

    @Getter
    @Setter
    public static class MockResp {

        @TLVField(tag = 0, type = TLVTypeEnum.BYTES)
        private byte[] rawResponse;
    }
}
