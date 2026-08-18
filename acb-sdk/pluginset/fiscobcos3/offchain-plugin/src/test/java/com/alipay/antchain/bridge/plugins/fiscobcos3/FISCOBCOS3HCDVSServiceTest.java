package com.alipay.antchain.bridge.plugins.fiscobcos3;

import cn.hutool.core.util.HexUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alipay.antchain.bridge.commons.core.base.ConsensusState;
import com.alipay.antchain.bridge.commons.core.base.CrossChainDomain;
import com.alipay.antchain.bridge.commons.core.bta.BlockchainTrustAnchorV1;
import com.alipay.antchain.bridge.plugins.lib.HeteroChainDataVerifierService;
import com.alipay.antchain.bridge.plugins.spi.ptc.core.VerifyResult;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class FISCOBCOS3HCDVSServiceTest {

    private static final byte[] RAW_MESSAGE = "hello-fiscobcos3-monitor".getBytes(StandardCharsets.UTF_8);

    @Test
    public void testHcdvsAnnotation() {
        HeteroChainDataVerifierService annotation = FISCOBCOS3HCDVSService.class.getAnnotation(
                HeteroChainDataVerifierService.class
        );
        Assert.assertNotNull(annotation);
        Assert.assertArrayEquals(new String[]{"fiscobcos3"}, annotation.products());
        Assert.assertArrayEquals(new String[]{"plugin-fiscobcos3"}, annotation.pluginId());
    }

    @Test
    public void testParseMessageFromLedgerData() {
        FISCOBCOS3HCDVSService service = new FISCOBCOS3HCDVSService();
        byte[] parsed = service.parseMessageFromLedgerData(buildLedgerData());
        Assert.assertArrayEquals(RAW_MESSAGE, parsed);
    }

    @Test
    public void testVerifyAnchorConsensusStateAcceptsArrayEndorsements() {
        FISCOBCOS3HCDVSService service = new FISCOBCOS3HCDVSService();
        String amContract = "0xaae7883add05cbae42dd3353222482460fa08467";
        JSONArray sealers = buildSealers(4);

        BlockchainTrustAnchorV1 bta = new BlockchainTrustAnchorV1();
        bta.setDomain(new CrossChainDomain("fisco.web3.monitor.01"));
        bta.setAmId(buildAmId(amContract));
        JSONObject subjectIdentity = new JSONObject();
        subjectIdentity.put("amContract", amContract);
        JSONObject consensusNodeInfo = new JSONObject();
        consensusNodeInfo.put("sealerList", sealers);
        subjectIdentity.put("consensusNodeInfo", consensusNodeInfo);
        bta.setSubjectIdentity(subjectIdentity.toJSONString().getBytes(StandardCharsets.UTF_8));

        ConsensusState anchorState = new ConsensusState(
                new CrossChainDomain("fisco.web3.monitor.01"),
                BigInteger.valueOf(376L),
                HexUtil.decodeHex("2e97f3b39bac450acab1b1a9b79d8c8e009962cf7b08553be64945ca5d9434ae"),
                HexUtil.decodeHex("2e97f3b39bac450acab1b1a9b79d8c8e009962cf7b08553be64945ca5d9434ae"),
                0L,
                buildStateData(),
                new byte[0],
                buildSignatureArray(4).toJSONString().getBytes(StandardCharsets.UTF_8)
        );

        VerifyResult result = service.verifyAnchorConsensusState(bta, anchorState);
        Assert.assertTrue(result.isSuccess());
    }

    @Test
    public void testVerifyAnchorConsensusStateAcceptsHexSealerIndex() {
        FISCOBCOS3HCDVSService service = new FISCOBCOS3HCDVSService();
        String amContract = "0xaae7883add05cbae42dd3353222482460fa08467";
        JSONArray sealers = buildSealers(4);

        BlockchainTrustAnchorV1 bta = new BlockchainTrustAnchorV1();
        bta.setDomain(new CrossChainDomain("fisco.web3.monitor.01"));
        bta.setAmId(buildAmId(amContract));
        JSONObject subjectIdentity = new JSONObject();
        subjectIdentity.put("amContract", amContract);
        JSONObject consensusNodeInfo = new JSONObject();
        consensusNodeInfo.put("sealerList", sealers);
        subjectIdentity.put("consensusNodeInfo", consensusNodeInfo);
        bta.setSubjectIdentity(subjectIdentity.toJSONString().getBytes(StandardCharsets.UTF_8));

        ConsensusState anchorState = new ConsensusState(
                new CrossChainDomain("fisco.web3.monitor.01"),
                BigInteger.valueOf(416L),
                HexUtil.decodeHex("484f1e7c2f747e5450d8dfffb01e88e0548c8f8654cf45e632474b7004f79f26"),
                HexUtil.decodeHex("484f1e7c2f747e5450d8dfffb01e88e0548c8f8654cf45e632474b7004f79f26"),
                0L,
                buildStateData(),
                new byte[0],
                buildSignatureArray(4, true).toJSONString().getBytes(StandardCharsets.UTF_8)
        );

        VerifyResult result = service.verifyAnchorConsensusState(bta, anchorState);
        Assert.assertTrue(result.isSuccess());
    }

    private byte[] buildLedgerData() {
        JSONObject receipt = new JSONObject();
        JSONArray logs = new JSONArray();
        JSONObject log = new JSONObject();
        JSONArray topics = new JSONArray();
        topics.add("0x213824f091e217a49bb20ab77821abf67ebb69b78772e370a2141444297ef60c");
        log.put("topics", topics);
        log.put("data", encodeBytesEventData(HexUtil.encodeHexStr(RAW_MESSAGE).getBytes(StandardCharsets.UTF_8)));
        logs.add(log);
        receipt.put("logEntries", logs);
        return receipt.toJSONString().getBytes(StandardCharsets.UTF_8);
    }

    private JSONArray buildSealers(int count) {
        JSONArray sealers = new JSONArray();
        for (int i = 0; i < count; i++) {
            sealers.add(String.format("%0128d", i + 1));
        }
        return sealers;
    }

    private JSONArray buildSignatureArray(int count) {
        return buildSignatureArray(count, false);
    }

    private JSONArray buildSignatureArray(int count, boolean hexIndex) {
        JSONArray signatures = new JSONArray();
        for (int i = 0; i < count; i++) {
            JSONObject signature = new JSONObject();
            signature.put("sealerIndex", hexIndex ? "0x" + Integer.toHexString(i) : String.valueOf(i));
            signature.put("signature", "0x" + String.format("%0130d", i + 1));
            signatures.add(signature);
        }
        return signatures;
    }

    private byte[] buildStateData() {
        JSONObject stateData = new JSONObject();
        stateData.put("transactionsRoot", "0x01");
        stateData.put("receiptsRoot", "0x02");
        stateData.put("stateRoot", "0x03");
        return stateData.toJSONString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildAmId(String amContract) {
        byte[] amId = new byte[32];
        byte[] amAddress = HexUtil.decodeHex(amContract.substring(2));
        System.arraycopy(amAddress, 0, amId, 12, amAddress.length);
        return amId;
    }

    private String encodeBytesEventData(byte[] data) {
        byte[] offset = new byte[32];
        offset[31] = 32;
        byte[] length = new byte[32];
        length[31] = (byte) data.length;
        int paddedLength = ((data.length + 31) / 32) * 32;
        byte[] paddedData = new byte[paddedLength];
        System.arraycopy(data, 0, paddedData, 0, data.length);
        return "0x" + HexUtil.encodeHexStr(offset) + HexUtil.encodeHexStr(length) + HexUtil.encodeHexStr(paddedData);
    }
}
