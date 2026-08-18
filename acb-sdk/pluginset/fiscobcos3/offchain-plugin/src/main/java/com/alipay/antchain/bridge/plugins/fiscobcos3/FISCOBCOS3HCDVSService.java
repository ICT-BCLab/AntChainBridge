package com.alipay.antchain.bridge.plugins.fiscobcos3;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alipay.antchain.bridge.commons.core.base.ConsensusState;
import com.alipay.antchain.bridge.commons.core.base.CrossChainMessage;
import com.alipay.antchain.bridge.commons.core.bta.IBlockchainTrustAnchor;
import com.alipay.antchain.bridge.plugins.lib.HeteroChainDataVerifierService;
import com.alipay.antchain.bridge.plugins.spi.ptc.AbstractHCDVSService;
import com.alipay.antchain.bridge.plugins.spi.ptc.core.VerifyResult;

import java.math.BigInteger;
import java.util.Arrays;

@HeteroChainDataVerifierService(products = "fiscobcos3", pluginId = "plugin-fiscobcos3")
public class FISCOBCOS3HCDVSService extends AbstractHCDVSService {

    private static final String SEND_AUTH_MESSAGE_TOPIC = "0x213824f091e217a49bb20ab77821abf67ebb69b78772e370a2141444297ef60c";

    @Override
    public VerifyResult verifyAnchorConsensusState(IBlockchainTrustAnchor bta, ConsensusState anchorState) {
        getHCDVSLogger().info(
                "start verifying FISCO BCOS v2 anchor consensus state (height: {}, hash: {}) for domain {}",
                anchorState.getHeight(), anchorState.getHashHex(), bta.getDomain()
        );

        try {
            JSONObject subjectIdentity = JSON.parseObject(new String(bta.getSubjectIdentity()));
            String amContractInBta = subjectIdentity.getString("amContract");
            if (StrUtil.isBlank(amContractInBta)) {
                return VerifyResult.fail("missing amContract in BTA subject identity");
            }

            byte[] amIdInBta = HexUtil.decodeHex(StrUtil.removePrefix(amContractInBta, "0x"));
            if (!checkAmContract(amIdInBta, bta.getAmId())) {
                getHCDVSLogger().error(
                        "AM contract mismatch, BTA subject has {}, but BTA amId is {}",
                        amContractInBta, HexUtil.encodeHexStr(bta.getAmId())
                );
                return VerifyResult.fail("AM contract mismatch");
            }

            JSONObject stateData = JSON.parseObject(new String(anchorState.getStateData()));
            if (!validateStateData(stateData)) {
                return VerifyResult.fail("invalid state data");
            }

            JSONArray sealerList = getSealerList(anchorState, subjectIdentity);
            if (sealerList == null || sealerList.isEmpty()) {
                return VerifyResult.fail("empty consensus node list");
            }

            JSONArray signatureList = getSignatureList(anchorState);
            if (signatureList == null || signatureList.isEmpty()) {
                return VerifyResult.fail("empty signature list");
            }

            int minRequiredSignatures = calculateMinRequiredSignatures(sealerList.size());
            if (signatureList.size() < minRequiredSignatures) {
                return VerifyResult.fail(
                        "not enough signatures: require {}, actual {}",
                        minRequiredSignatures, signatureList.size()
                );
            }

            int validSignatureCount = validateSignatureEntries(signatureList, sealerList);
            if (validSignatureCount < minRequiredSignatures) {
                return VerifyResult.fail(
                        "not enough valid signature entries: require {}, actual {}",
                        minRequiredSignatures, validSignatureCount
                );
            }

            if (anchorState.getConsensusNodeInfo() == null || anchorState.getConsensusNodeInfo().length == 0) {
                JSONObject consensusNodeInfo = new JSONObject();
                consensusNodeInfo.put("sealerList", sealerList);
                anchorState.setConsensusNodeInfo(consensusNodeInfo.toJSONString().getBytes());
            }

            getHCDVSLogger().info(
                    "successfully verified FISCO BCOS v2 anchor consensus state (height: {}, hash: {})",
                    anchorState.getHeight(), anchorState.getHashHex()
            );
            return VerifyResult.success();
        } catch (Exception e) {
            getHCDVSLogger().error("failed to verify FISCO BCOS v2 anchor consensus state", e);
            return VerifyResult.fail("failed to verify anchor consensus state: {}", e.getMessage());
        }
    }

    @Override
    public VerifyResult verifyConsensusState(ConsensusState stateToVerify, ConsensusState parentState) {
        getHCDVSLogger().info(
                "start verifying FISCO BCOS v2 consensus state (height: {}, hash: {}) by parent (height: {}, hash: {})",
                stateToVerify.getHeight(), stateToVerify.getHashHex(), parentState.getHeight(), parentState.getHashHex()
        );

        try {
            if (!validateStateData(JSON.parseObject(new String(parentState.getStateData())))) {
                return VerifyResult.fail("invalid parent state data");
            }
            if (!validateStateData(JSON.parseObject(new String(stateToVerify.getStateData())))) {
                return VerifyResult.fail("invalid current state data");
            }

            String childParentHash = normalizeHex(HexUtil.encodeHexStr(stateToVerify.getParentHash()));
            String parentHash = normalizeHex(parentState.getHashHex());
            if (!childParentHash.equalsIgnoreCase(parentHash)) {
                getHCDVSLogger().error(
                        "invalid block linkage, child parent hash {} does not equal parent hash {}",
                        childParentHash, parentHash
                );
                return VerifyResult.fail("invalid parent hash");
            }

            JSONArray sealerList = getSealerList(parentState, null);
            if (sealerList == null || sealerList.isEmpty()) {
                return VerifyResult.fail("empty consensus node list");
            }

            JSONArray signatureList = getSignatureList(stateToVerify);
            if (signatureList == null || signatureList.isEmpty()) {
                return VerifyResult.fail("empty signature list");
            }

            int minRequiredSignatures = calculateMinRequiredSignatures(sealerList.size());
            if (signatureList.size() < minRequiredSignatures) {
                return VerifyResult.fail(
                        "not enough signatures: require {}, actual {}",
                        minRequiredSignatures, signatureList.size()
                );
            }

            int validSignatureCount = validateSignatureEntries(signatureList, sealerList);
            if (validSignatureCount < minRequiredSignatures) {
                return VerifyResult.fail(
                        "not enough valid signature entries: require {}, actual {}",
                        minRequiredSignatures, validSignatureCount
                );
            }

            stateToVerify.setConsensusNodeInfo(parentState.getConsensusNodeInfo());

            getHCDVSLogger().info(
                    "successfully verified FISCO BCOS v2 consensus state (height: {}, hash: {})",
                    stateToVerify.getHeight(), stateToVerify.getHashHex()
            );
            return VerifyResult.success();
        } catch (Exception e) {
            getHCDVSLogger().error("failed to verify FISCO BCOS v2 consensus state", e);
            return VerifyResult.fail("failed to verify consensus state: {}", e.getMessage());
        }
    }

    @Override
    public VerifyResult verifyCrossChainMessage(CrossChainMessage message, ConsensusState currState) {
        if (message.getProvableData() == null) {
            return VerifyResult.fail("cross-chain message has no provable data");
        }

        try {
            long messageHeight = message.getProvableData().getHeight();
            long stateHeight = currState.getHeight().longValue();
            if (messageHeight != stateHeight) {
                return VerifyResult.fail("message height {} does not match consensus state height {}", messageHeight, stateHeight);
            }

            JSONObject stateData = JSON.parseObject(new String(currState.getStateData()));
            String receiptsRootInState = stateData.getString("receiptsRoot");
            if (StrUtil.isBlank(receiptsRootInState)) {
                return VerifyResult.fail("missing receiptsRoot in consensus state");
            }

            JSONObject receipt = JSON.parseObject(new String(message.getProvableData().getLedgerData()));
            String txHash = normalizeHex(HexUtil.encodeHexStr(message.getProvableData().getTxHash()));
            String receiptTxHash = normalizeHex(receipt.getString("transactionHash"));
            if (!txHash.equalsIgnoreCase(receiptTxHash)) {
                return VerifyResult.fail("transaction hash mismatch");
            }

            JSONObject proof = JSON.parseObject(new String(message.getProvableData().getProof()));
            String receiptsRootInProof = proof.getString("receiptsRoot");
            if (StrUtil.isNotBlank(receiptsRootInProof) && !receiptsRootInState.equalsIgnoreCase(receiptsRootInProof)) {
                return VerifyResult.fail("receipts root mismatch");
            }

            byte[] amMessage = parseMessageFromLedgerData(message.getProvableData().getLedgerData());
            if (!Arrays.equals(amMessage, message.getMessage())) {
                return VerifyResult.fail("auth message in ledger data does not match cross-chain message");
            }

            getHCDVSLogger().info(
                    "successfully verified FISCO BCOS v2 cross-chain message (height: {}, txHash: {})",
                    messageHeight, txHash
            );
            return VerifyResult.success();
        } catch (Exception e) {
            getHCDVSLogger().error("failed to verify FISCO BCOS v2 cross-chain message", e);
            return VerifyResult.fail("failed to verify cross-chain message: {}", e.getMessage());
        }
    }

    @Override
    public byte[] parseMessageFromLedgerData(byte[] ledgerData) {
        JSONObject receipt = JSON.parseObject(new String(ledgerData));
        if (receipt == null) {
            return new byte[0];
        }
        AuthMessageEventResult eventResult = findAuthMessageEvent(getLogEntries(receipt));
        if (eventResult == null) {
            return new byte[0];
        }
        return eventResult.getMessage();
    }

    private JSONArray getLogEntries(JSONObject receipt) {
        JSONArray logEntries = receipt.getJSONArray("logEntries");
        if (logEntries == null || logEntries.isEmpty()) {
            logEntries = receipt.getJSONArray("logs");
        }
        return logEntries;
    }

    private JSONArray getSealerList(ConsensusState state, JSONObject subjectIdentity) {
        if (state.getConsensusNodeInfo() != null && state.getConsensusNodeInfo().length > 0) {
            JSONObject consensusNodeInfo = JSON.parseObject(new String(state.getConsensusNodeInfo()));
            JSONArray sealers = consensusNodeInfo.getJSONArray("sealerList");
            if (sealers != null && !sealers.isEmpty()) {
                return sealers;
            }
        }

        if (subjectIdentity != null) {
            JSONObject consensusNodeInfo = subjectIdentity.getJSONObject("consensusNodeInfo");
            if (consensusNodeInfo != null) {
                return consensusNodeInfo.getJSONArray("sealerList");
            }
        }
        return null;
    }

    private JSONArray getSignatureList(ConsensusState state) {
        if (state.getEndorsements() == null || state.getEndorsements().length == 0) {
            return null;
        }
        Object endorsements = JSON.parse(new String(state.getEndorsements()));
        if (endorsements instanceof JSONArray) {
            return (JSONArray) endorsements;
        }
        if (endorsements instanceof JSONObject) {
            return ((JSONObject) endorsements).getJSONArray("signatures");
        }
        return null;
    }

    private boolean validateStateData(JSONObject stateData) {
        if (stateData == null) {
            return false;
        }

        String receiptsRoot = stateData.getString("receiptsRoot");
        String transactionsRoot = stateData.getString("transactionsRoot");
        String stateRoot = stateData.getString("stateRoot");
        return StrUtil.isNotBlank(receiptsRoot)
                && StrUtil.isNotBlank(transactionsRoot)
                && StrUtil.isNotBlank(stateRoot);
    }

    private int calculateMinRequiredSignatures(int totalNodes) {
        return totalNodes - (totalNodes - 1) / 3;
    }

    private int validateSignatureEntries(JSONArray signatureList, JSONArray sealerList) {
        int validSignatureCount = 0;

        for (int i = 0; i < signatureList.size(); i++) {
            JSONObject signatureInfo = signatureList.getJSONObject(i);
            String indexStr = signatureInfo.getString("sealerIndex");
            if (StrUtil.isBlank(indexStr)) {
                continue;
            }

            int sealerIndex = parseSealerIndex(indexStr);
            if (sealerIndex < 0 || sealerIndex >= sealerList.size()) {
                continue;
            }

            try {
                String signature = signatureInfo.getString("signature");
                String nodeId = sealerList.getString(sealerIndex);
                if (StrUtil.isNotBlank(nodeId)
                        && StrUtil.isNotBlank(signature)
                        && HexUtil.decodeHex(StrUtil.removePrefix(signature, "0x")).length > 0) {
                    validSignatureCount++;
                }
            } catch (Exception e) {
                getHCDVSLogger().warn("invalid sealer {} signature entry: {}", sealerIndex, e.getMessage());
            }
        }

        return validSignatureCount;
    }

    private int parseSealerIndex(String indexStr) {
        String normalizedIndex = StrUtil.trim(indexStr);
        if (StrUtil.startWithIgnoreCase(normalizedIndex, "0x")) {
            return Integer.parseInt(StrUtil.subAfter(normalizedIndex, "0x", false), 16);
        }
        return Integer.parseInt(normalizedIndex);
    }

    private boolean checkAmContract(byte[] amIdInBta, byte[] amIdInState) {
        if (amIdInBta == null || amIdInState == null) {
            return false;
        }
        if (amIdInBta.length == 20 && amIdInState.length == 32) {
            return Arrays.equals(amIdInBta, Arrays.copyOfRange(amIdInState, 12, 32));
        }
        return Arrays.equals(amIdInBta, amIdInState);
    }

    private AuthMessageEventResult findAuthMessageEvent(JSONArray logEntries) {
        if (logEntries == null || logEntries.isEmpty()) {
            return null;
        }

        for (int i = 0; i < logEntries.size(); i++) {
            JSONObject logEntry = logEntries.getJSONObject(i);
            JSONArray topics = logEntry.getJSONArray("topics");
            if (topics == null || topics.isEmpty()) {
                continue;
            }

            if (SEND_AUTH_MESSAGE_TOPIC.equalsIgnoreCase(normalizeHex(topics.getString(0)))) {
                byte[] amMessage = extractAMMessageFromData(logEntry.getString("data"));
                if (amMessage.length > 0) {
                    return new AuthMessageEventResult(i, amMessage, logEntry);
                }
            }
        }

        return null;
    }

    private byte[] extractAMMessageFromData(String data) {
        if (StrUtil.isBlank(data)) {
            return new byte[0];
        }

        try {
            byte[] dataBytes = HexUtil.decodeHex(StrUtil.removePrefix(data, "0x"));
            if (dataBytes.length < 64) {
                return new byte[0];
            }

            int offset = new BigInteger(1, Arrays.copyOfRange(dataBytes, 0, 32)).intValue();
            if (offset < 0 || offset + 32 > dataBytes.length) {
                return new byte[0];
            }

            int length = new BigInteger(1, Arrays.copyOfRange(dataBytes, offset, offset + 32)).intValue();
            if (length < 0 || offset + 32 + length > dataBytes.length) {
                return new byte[0];
            }

            byte[] payload = Arrays.copyOfRange(dataBytes, offset + 32, offset + 32 + length);
            String pkgHex = new String(payload);
            if (pkgHex.matches("(?i)^[0-9a-f]+$")) {
                return HexUtil.decodeHex(pkgHex);
            }
            return payload;
        } catch (Exception e) {
            getHCDVSLogger().warn("failed to extract AM message from event data: {}", e.getMessage());
            return new byte[0];
        }
    }

    private String normalizeHex(String hex) {
        if (StrUtil.isBlank(hex)) {
            return "";
        }
        return StrUtil.prependIfMissing(StrUtil.removePrefix(hex, "0x"), "0x");
    }

    private static class AuthMessageEventResult {
        private final int index;
        private final byte[] message;
        private final JSONObject logEntry;

        private AuthMessageEventResult(int index, byte[] message, JSONObject logEntry) {
            this.index = index;
            this.message = message;
            this.logEntry = logEntry;
        }

        public int getIndex() {
            return index;
        }

        public byte[] getMessage() {
            return message;
        }

        public JSONObject getLogEntry() {
            return logEntry;
        }
    }
}
