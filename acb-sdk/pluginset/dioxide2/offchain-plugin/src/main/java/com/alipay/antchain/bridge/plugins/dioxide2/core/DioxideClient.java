package com.alipay.antchain.bridge.plugins.dioxide2.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.net.http.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import com.alipay.antchain.bridge.commons.core.base.CrossChainMessageReceipt;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideAddressTypeEnum;
import com.alipay.antchain.bridge.plugins.dioxide2.gcl.*;
import com.alipay.antchain.bridge.commons.core.base.CrossChainMessage;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideConfig;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideTypes;

@Getter
public class DioxideClient {

    private final Logger bbcLogger;

    private final DioxideConfig config;

    private final DioxideAccount dioxideAccount;

    private final DioxideAddress dappAddress;

    private final HttpClient httpClient;

    private final ExecutorService executor;

    private static final int DEFAULT_TIMEOUT = 20 * 1000;

    private static final List<String> PRE_CONTRACT_ORDER = List.of(
            Interfaces.IAUTHMESSAGE,
            Interfaces.ICONTRACTUSINGSDP,
            Interfaces.ISDPMESSAGE,
            Interfaces.ISUBPROTOCOL,
            Interfaces.ICONTRACTUSINGMONITOR,
            Interfaces.IMONITOR,
            Libs.UTILS,
            Libs.SIZEOF,
            Libs.BYTESTOTYPES,
            Libs.TYPESTOBYTES,
            Libs.TLVUTILS,
            Libs.MONITORLIB,
            Libs.SDPLIB,
            Libs.AMLIB
    );

    private static final String SEND_AUTH_MESSAGE_EVENT_NAME = "SendAuthMessage:name";

    private static final String RECV_MESSAGE_IN_PROTOCOL = "recvMessageInProtocol:name";

    public DioxideClient(DioxideConfig config, Logger bbcLogger) {
        this.bbcLogger = bbcLogger;
        this.config = config;

        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        httpClient = HttpClient.newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // test if rpcUrl is true
        try {
            String rawResp = makeRequest("dx.overview", "");
            checkIfErrorResponse(rawResp);
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to connect dioxide (url: %s) or other error (exception: %s)", config.getRpcUrl(), e.getMessage()), e);
        }

        this.dioxideAccount = DioxideAccount.fromKey(this.config.getPrivateKey());
        dappAddress = new DioxideAddress(this.config.getDappName(), DioxideAddressTypeEnum.DAPP);
    }

    public void shutdown() {
        this.executor.shutdown();
        this.httpClient.close();
    }

    @SneakyThrows
    public Long queryLatestHeight() {
        String rawResp = makeRequest("dx.committed_head_height", "");
        JSONObject resp = checkIfErrorResponse(rawResp);
        return resp.getLong("HeadHeight");
    }

    public DioxideTransaction getTransactionByHash(String txHash) {
        String rawResp = makeRequest("dx.transaction", JSON.toJSONString(orderedMap("hash", txHash)));
        JSONObject ret = checkIfErrorResponse(rawResp);
        try {
            return JSON.toJavaObject(ret, DioxideTransaction.class);
        } catch (Exception e) {
            getBbcLogger().error("[getTransactionByHash] error: {}", e.getMessage());
            getBbcLogger().info("\n{}", JSON.toJSONString(ret, SerializerFeature.PrettyFormat));
            return null;
        }
    }

    public JSONObject getTransactionJonObjectByHash(String txHash) {
        String rawResp = makeRequest("dx.transaction", JSON.toJSONString(orderedMap("hash", txHash)));
        return checkIfErrorResponse(rawResp);
    }

    public DioxideTransactionBlock getTransactionBlockInGlobalShardByHeight(long height) {
        String rawResp = makeRequest("dx.transaction_block", JSON.toJSONString(orderedMap(
                "query_type", 0,
                "shard_index", DioxideTypes.GLOBAL_IDENTIFIER,
                "height", height
        )));
        JSONObject ret = checkIfErrorResponse(rawResp);
        try {
            return JSON.toJavaObject(ret, DioxideTransactionBlock.class);
        } catch (Exception e) {
            getBbcLogger().error(e.getMessage(), e);
            getBbcLogger().info("\n{}", JSON.toJSONString(ret, SerializerFeature.PrettyFormat));
            return null;
        }
    }

    public JSONObject getConsensusHeaderByHeight(long height) {
        String rawResp = makeRequest("dx.consensus_header", JSON.toJSONString(orderedMap(
                "query_type", 0,
                "height", height
        )));
        return checkIfErrorResponse(rawResp);
    }

    public CrossChainMessageReceipt getCrossChainMessageReceipt(DioxideTransaction dioxideTransaction) {
        CrossChainMessageReceipt crossChainMessageReceipt = new CrossChainMessageReceipt();
        // check if tx0 is null
        if (dioxideTransaction == null) {
            crossChainMessageReceipt.setConfirmed(false);
            crossChainMessageReceipt.setSuccessful(false);
            crossChainMessageReceipt.setTxhash("");
            crossChainMessageReceipt.setErrorMsg("tx0 is null");
            return crossChainMessageReceipt;
        }

        // check if tx0 is confirmed
        Long currHeight = queryLatestHeight();
        if (dioxideTransaction.getHeight() == null || dioxideTransaction.getHeight().compareTo(currHeight) > 0) {
            crossChainMessageReceipt.setConfirmed(false);
            crossChainMessageReceipt.setSuccessful(true);
            crossChainMessageReceipt.setTxhash(dioxideTransaction.getTxHash());
            crossChainMessageReceipt.setErrorMsg("tx0 is not confirmed");
            return crossChainMessageReceipt;
        }

        // check if tx1(inbound relay) in tx0 is confirmed
        if (dioxideTransaction.getInvocation() == null || CollUtil.isEmpty(dioxideTransaction.getInvocation().getRelays())) {
            crossChainMessageReceipt.setConfirmed(false);
            crossChainMessageReceipt.setSuccessful(true);
            crossChainMessageReceipt.setTxhash(dioxideTransaction.getTxHash());
            crossChainMessageReceipt.setErrorMsg("tx1(inbound relay) in tx0 is not confirmed");
            return crossChainMessageReceipt;
        }

        // check if tx2(contain recvMessageInProtocol event txHash) in tx1 is confirmed and successful
        List<String> tx2Hashes = dioxideTransaction.getInvocation().getRelays().stream()
                .filter(Objects::nonNull)
                .map(s -> {
                    int idx = s.indexOf(':');
                    return idx >= 0 ? s.substring(0, idx) : s;
                })
                .distinct()
                .toList();
        for (String tx2Hash : tx2Hashes) {
            JSONObject tx2 = getTransactionJonObjectByHash(tx2Hash);
            // check if tx3 (is recvMessageInProtocol event tx) in tx2 is confirmed and successful
            JSONArray eventRelays = tx2.getJSONArray("Relays");
            if (CollUtil.isEmpty(eventRelays)) {
                crossChainMessageReceipt.setConfirmed(false);
                crossChainMessageReceipt.setSuccessful(true);
                crossChainMessageReceipt.setTxhash(dioxideTransaction.getTxHash());
                crossChainMessageReceipt.setErrorMsg("recvMessageInProtocol event in protocol contract is not confirmed");
                return crossChainMessageReceipt;
            }
            List<DioxideTransaction> eventTxs = eventRelays.toJavaList(DioxideTransaction.class);
            for (DioxideTransaction eventTx : eventTxs) {
                if (eventTx.getInvocation() != null && CollUtil.isNotEmpty(eventTx.getInvocation().getRelays())) {
                    // find recvMessageInProtocol event
                    List<String> tx3Hashes = eventTx.getInvocation().getRelays();
                    for (String tx3Hash : tx3Hashes) {
                        DioxideTransaction dtx = getTransactionByHash(tx3Hash);
                        if (dtx != null && StrUtil.isNotEmpty(dtx.getTarget()) && dtx.getTarget().equals(DioxideClient.RECV_MESSAGE_IN_PROTOCOL)) {
                            crossChainMessageReceipt.setConfirmed(true);
                            crossChainMessageReceipt.setSuccessful(true);
                            crossChainMessageReceipt.setTxhash(dioxideTransaction.getTxHash());
                            crossChainMessageReceipt.setErrorMsg("");
                            return crossChainMessageReceipt;
                        }
                    }
                }
            }
        }

        // not find recvMessageInProtocol event: tx is confirmed but failed?
        crossChainMessageReceipt.setConfirmed(true);
        crossChainMessageReceipt.setSuccessful(false);
        crossChainMessageReceipt.setTxhash(dioxideTransaction.getTxHash());
        crossChainMessageReceipt.setErrorMsg("no recvMessageInProtocol event is protocol contract");

        return crossChainMessageReceipt;
    }


    public List<CrossChainMessage> readAuthMessagesFromBlock(long height) {
        List<CrossChainMessage> messageList = ListUtil.toList();
        // 1. get tx block on shard where AM is located
        DioxideTransactionBlock block = getTransactionBlockInGlobalShardByHeight(height);
        // 2. get tx from tx block
        List<String> dispatchedAndOutboundRelays = ListUtil.toList();
        if (block.getDispatchedRelayTxnCount() != 0) {
            dispatchedAndOutboundRelays.addAll(block.getTransactions().getDispatchedRelays());
        }
        if (block.getOutboundRelayTxnCount() != 0) {
            dispatchedAndOutboundRelays.addAll(block.getTransactions().getOutboundRelays());
        }
        if (CollectionUtils.isNotEmpty(dispatchedAndOutboundRelays)) {
            dispatchedAndOutboundRelays.forEach(txn -> {
                DioxideTransaction dtx = getTransactionByHash(txn);
                if (StrUtil.isNotEmpty(dtx.getTarget())) {
                    if (dtx.getTarget().equals(SEND_AUTH_MESSAGE_EVENT_NAME) &&
                        StrUtil.contains(dtx.getFunction(), config.getDappName())) {
                        getBbcLogger().info("send am event found in global shard, block: {}, block hash: {}, contract name: {}",
                                block.getHeight(), block.getHash(), String.format("%s:%s", config.getDappName(), config.getAmContractName()));
                        messageList.add(CrossChainMessage.createCrossChainMessage(
                                CrossChainMessage.CrossChainMessageType.AUTH_MSG,
                                dtx.getHeight(),
                                getConsensusHeaderByHeight(height).getLongValue("Timestamp"),
                                // NOTICE: use sha256 to ensure this blockHash have 32 bytes
                                DigestUtil.sha256(block.getHash()),
                                deserializedSendMsgEventArgs(dtx.getInputAsString()),
                                // todo: put ledger data, for SPV or other attestations
                                // this time we need no verify. it's ok to set it with empty bytes
                                "".getBytes(),
                                // todo: put proof data
                                // this time we need no proof data. it's ok to set it with empty bytes
                                "".getBytes(),
                                // NOTICE: use sha256 to ensure this txHash have 32 bytes
                                DigestUtil.sha256(dtx.getTxHash())
//                                dtx.getTxHash().getBytes(StandardCharsets.UTF_8)

                        ));
                    }
                }
            });
            return messageList;
        }

        if (!messageList.isEmpty()) {
            getBbcLogger().info("read cross chain messages (blockNumber: {}, msg_size: {})", height, messageList.size());
            getBbcLogger().debug("read cross chain messages (blockNumber: {}, msgs: {})",
                    height,
                    messageList.stream().map(JSON::toJSONString).collect(Collectors.joining(","))
            );
        }

        return ListUtil.empty();
    }

    private byte[] deserializedSendMsgEventArgs(String input) {
        // 1. hex 转 byte[]
        byte[] inputBytes = HexUtil.decodeHex(input);

        // 2. 检查长度是否至少包含 4 字节
        if (inputBytes.length < 4) {
            throw new IllegalArgumentException("illegal input bytes length: " + inputBytes.length);
        }

        // 3. 解析前 4 字节（小端序）作为长度字段
        int len = ((inputBytes[3] & 0xFF) << 24) |
                ((inputBytes[2] & 0xFF) << 16) |
                ((inputBytes[1] & 0xFF) << 8)  |
                ((inputBytes[0] & 0xFF));

        // 4. 校验后续字节长度是否与长度字段一致
        if (inputBytes.length != 4 + len) {
            throw new IllegalArgumentException("Length field mismatch: expected " + len +
                    " bytes, but got " + (inputBytes.length - 4));
        }

        // 5. 拷贝数据字段（丢弃前4字节）
        byte[] evnetData = new byte[len];
        System.arraycopy(inputBytes, 4, evnetData, 0, len);

        return evnetData;
    }

    public long deployAuthMsgContract() {
        try {
            deployPreContract();

            List<String> codes = new ArrayList<>();
            List<String> cargs = new ArrayList<>();

            String amContractSource =
                    new String(
                            Base64.getDecoder().decode(Contracts.AUTHMSG),
                            StandardCharsets.UTF_8
                    );

            codes.add(amContractSource);
            cargs.add(JSON.toJSONString(orderedMap(
                    "_owner", dioxideAccount.getAddressInString(),
                    "_relayer", dioxideAccount.getAddressInString()
            )));

            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "delegatee", dappAddress.getAddressInString(),
                    "function", "core.delegation.deploy_contracts",
                    "args", orderedMap("code", codes, "cargs", cargs))),
                    true
            );
            waitForContractDeployed(txHash);
            // test if the contract been deployed successfully, and get the contract name
            return getContractCid(config.getAmContractName());
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to deploy contract: %s", config.getAmContractName()) ,e);
        }
    }

    public long deploySdpContract() {
        try {
            deployPreContract();

            List<String> codes = new ArrayList<>();
            List<String> cargs = new ArrayList<>();

            String sdpContractSource =
                    new String(
                            Base64.getDecoder().decode(Contracts.SDPMSG),
                            StandardCharsets.UTF_8
                    );

            codes.add(sdpContractSource);
            cargs.add(JSON.toJSONString(orderedMap(
                    "_owner", dioxideAccount.getAddressInString()
            )));

            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "delegatee", dappAddress.getAddressInString(),
                    "function", "core.delegation.deploy_contracts",
                    "args", orderedMap("code", codes, "cargs", cargs))),
                    true
            );
            waitForContractDeployed(txHash);
            // test if the contract been deployed successfully, and get the contract name
            return getContractCid(config.getSdpContractName());
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to deploy contract: %s", config.getSdpContractName()) ,e);
        }
    }

    public long deployMonitorContract() {
        try {
            deployPreContract();

            List<String> codes = new ArrayList<>();
            List<String> cargs = new ArrayList<>();

            String monitorContractSource =
                    new String(
                            Base64.getDecoder().decode(Contracts.MONITOR),
                            StandardCharsets.UTF_8
                    );

            codes.add(monitorContractSource);
            cargs.add(JSON.toJSONString(orderedMap(
                    "_owner", dioxideAccount.getAddressInString()
            )));

            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "delegatee", dappAddress.getAddressInString(),
                    "function", "core.delegation.deploy_contracts",
                    "args", orderedMap("code", codes, "cargs", cargs))),
                    true
            );
            waitForContractDeployed(txHash);
            // test if the contract been deployed successfully, and get the contract name
            return getContractCid(config.getMonitorContractName());
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to deploy contract: %s", config.getMonitorContractName()) ,e);
        }
    }

    public long deployContract(String contractName, String contractSource, String cargsString) {
        try {
            deployPreContract();

            List<String> codes = new ArrayList<>();
            List<String> cargs = new ArrayList<>();

            codes.add(contractSource);
            cargs.add(cargsString);

            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "delegatee", dappAddress.getAddressInString(),
                    "function", "core.delegation.deploy_contracts",
                    "args", orderedMap("code", codes, "cargs", cargs))),
                    true
            );
            waitForContractDeployed(txHash);
            // test if the contract been deployed successfully, and get the contract name
            return getContractCid(contractName);
        } catch (Exception e) {
            throw new RuntimeException(String.format("failed to deploy contract: %s", contractName) ,e);
        }
    }

    private void deployPreContract() {
        if (config.getIsPreContractDeployed()) {
            getBbcLogger().info("[deployPreContract] is already deployed");
            return;
        }

        // mint some tokens for client
        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", "core.coin.mint",
                    "args", orderedMap(
                            "Amount", String.format("%d", (long) Math.pow(10, 18))
                    ))),
                    true
            );
            getBbcLogger().info("success to mint some tokens");
        } catch (Exception e) {
            throw new RuntimeException("failed to mint some tokens", e);
        }

        // deploy dapp
        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", "core.delegation.create",
                    "args", orderedMap(
                            "Type", 10,
                            "Name", config.getDappName(),
                            "Deposit", String.format("%d", (long) Math.pow(10, 11))
                            ))),
                    true
            );
            getBbcLogger().info("wait for deploying dapp: [txhash]{}", txHash);
            if (!waitForDappDeployed(txHash, DEFAULT_TIMEOUT)) {
                throw new RuntimeException("waitForDappDeployed failed");
            }
            getBbcLogger().info("success to deploy dapp: [txhash]{}", dappAddress.getAddressInString());
        } catch (Exception e) {
            throw new RuntimeException("failed to deploy dapp", e);
        }

        // deploy lib utils etc.
        try {
            List<String> codes = new ArrayList<>();
            List<String> cargs = new ArrayList<>();

            // 按 FILE_ORDER 顺序填充 codes 和 cargs
            for (String contractSource : PRE_CONTRACT_ORDER) {
                codes.add(new String(
                        Base64.getDecoder().decode(contractSource),
                        StandardCharsets.UTF_8
                ));
                cargs.add("");
            }

            getBbcLogger().info("dapp name to use: {}", dappAddress.getAddressInString());
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "delegatee", dappAddress.getAddressInString(),
                    "function", "core.delegation.deploy_contracts",
                    "args", orderedMap("code", codes, "cargs", cargs))),
                    true
            );
            waitForContractDeployed(txHash);
            getBbcLogger().info("deploy pre contract from onchain file: {}", txHash);
            config.setIsPreContractDeployed(true);
        } catch (Exception e) {
            throw new RuntimeException("failed to deploy pre contract", e);
        }
    }

    private long getContractCid(String contractName) {
        String rawResp = makeRequest("dx.contract_info", JSON.toJSONString(orderedMap(
                "contract", String.format("%s.%s", config.getDappName(), contractName)
        )));
        JSONObject resp = checkIfErrorResponse(rawResp);
        return resp.getLongValue("ContractVersionID");
    }

    public long querySdpSeq(String senderDomain, String senderID, String receiverDomain, String receiverID) {
        try {
            int[] senderDomainArray = toIntArray(senderDomain.getBytes(StandardCharsets.UTF_8));
            int[] senderIDArray = toIntArray(HexUtil.decodeHex(senderID));
            int[] receiverDomainArray = toIntArray(receiverDomain.getBytes(StandardCharsets.UTF_8));
            int[] receiverIDArray = toIntArray(HexUtil.decodeHex(receiverID));
            long seq;

            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", String.format("%s.%s.%s",config.getDappName(), config.getSdpContractName(), "querySDPMessageSeq"),
                    "args", orderedMap(
                            "senderDomain", senderDomainArray,
                            "senderID", senderIDArray,
                            "receiverDomain", receiverDomainArray,
                            "receiverID", receiverIDArray
                    ))),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }
            JSONObject resp = getContractState(config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Address, dioxideAccount.getAddressInString());
            JSONObject state = resp.getJSONObject("State");
            seq = state.getLongValue("resultInQuerySDPMessageSeq");

            getBbcLogger().info("result of sdp contract state [address]: \n{}", JSON.toJSONString(resp, SerializerFeature.PrettyFormat));

            getBbcLogger().info("sdpMsg seq: {} (senderDomain: {}, senderID: {}, receiverDomain: {}, receiverID: {})",
                    seq, senderDomain, senderID, receiverDomain, receiverID);
            return seq;
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("failed to query sdpMsg seq (senderDomain: %s, senderID: %s, receiverDomain: %s, receiverID: %s)",
                            senderDomain, senderID, receiverDomain, receiverID), e
            );
        }
    }

    public void setProtocolToAuthMsg(String protocolCidInString, String protocolType) {
        long protocolCid = Long.parseLong(protocolCidInString);
        String protocolAddress = String.format("0x%016X:contract", protocolCid);

        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", String.format("%s.%s.%s",config.getDappName(), config.getAmContractName(), "setProtocol"),
                    "args", orderedMap(
                            "protocolID", protocolCid,
                            "protocolAddress", protocolAddress,
                            "protocolType", Integer.parseInt(protocolType)
                    ))),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }
            getBbcLogger().info("set protocol (cid: {}, address: {}, type: {}) to AM {} by tx {} ",
                    protocolCid, protocolAddress, protocolType, config.getAmContractName(), txHash
            );
            JSONObject resp = getContractState(config.getDappName(), config.getAmContractName(), DioxideTypes.Scope.Global, "");
            getBbcLogger().info("result of am contract state: \n{}", JSON.toJSONString(resp, SerializerFeature.PrettyFormat));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to set protocol (cid: %s, address: %s, type: %s) to AM %s",
                            protocolCid, protocolAddress, protocolType, config.getAmContractName()
                    ), e
            );
        }
    }

    // NOTICE: 1.sync send tx, 2.truncate txHash to 32 bytes(temporary solution)
    public CrossChainMessageReceipt relayMsgToAuthMsg(byte[] rawMessage) {
        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", String.format("%s.%s.%s",config.getDappName(), config.getAmContractName(), "recvPkgFromRelayer"),
                    "args",  orderedMap(
                            "pkg", toIntArray(rawMessage)
                    ),
                    "gaslimit", 10000000)),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }

            CrossChainMessageReceipt crossChainMessageReceipt = new CrossChainMessageReceipt();
            crossChainMessageReceipt.setConfirmed(true);
            crossChainMessageReceipt.setSuccessful(true);
            // NOTICE: use sha256 to ensure txHash have 32 bytes
            // crossChainMessageReceipt.setTxhash(DigestUtil.sha256Hex(txHash));
            crossChainMessageReceipt.setTxhash(txHash);
            crossChainMessageReceipt.setErrorMsg("");
            getBbcLogger().info("relay am msg by tx {}", txHash);

            return crossChainMessageReceipt;

        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("failed to relay AM %s to %s",
                            HexUtil.encodeHexStr(rawMessage), config.getAmContractName()
                    ), e
            );
        }
    }

    public void setAmContractToSdp(String amContractCidInString) {
        long amContractCid = Long.parseLong(amContractCidInString);
        String amContractAddress = String.format("0x%016X:contract", amContractCid);

        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", String.format("%s.%s.%s",config.getDappName(), config.getSdpContractName(), "setAmContract"),
                    "args",  orderedMap(
                            "_amContractId", amContractCid,
                            "_amAddress",  amContractAddress
                    ))),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }
            getBbcLogger().info("set am contract (cid: {}, address: {}) to SDP {} by tx {}",
                    amContractCid, amContractAddress, config.getSdpContractName(), txHash);
            JSONObject resp = getContractState(config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Global, "");
            getBbcLogger().info("result of sdp contract state: \n{}", JSON.toJSONString(resp, SerializerFeature.PrettyFormat));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("failed to set am contract (cid: %s, address: %s) to SDP %s",
                            amContractCid, amContractAddress, config.getSdpContractName()
                    ), e
            );
        }
    }

    public void setLocalDomainToSdp(String localDomain) {
        try {
            int[] domainArray = toIntArray(localDomain.getBytes(StandardCharsets.UTF_8));
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                            "sender", dioxideAccount.getAddressInString(),
                            "function", String.format("%s.%s.setLocalDomain", config.getDappName(), config.getSdpContractName()),
                            "args", orderedMap(
                                    "domain", domainArray)
                    )),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }
            getBbcLogger().info("set domain ({}) to SDP {} by tx {}", localDomain, config.getSdpContractName(), txHash);
            JSONObject resp = getContractState(config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Global, "");
            getBbcLogger().info("result of sdp contract state: \n{}", JSON.toJSONString(resp, SerializerFeature.PrettyFormat));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to set domain (%s) to SDP %s", localDomain, config.getSdpContractName()
                    ), e
            );
        }
    }

    public void setMonitorContractToSdp(String monitorContractCidInString) {
        long monitorContractCid = Long.parseLong(monitorContractCidInString);
        String monitorContractAddress = String.format("0x%016X:contract", monitorContractCid);

        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", String.format("%s.%s.%s",config.getDappName(), config.getSdpContractName(), "setMonitorContract"),
                    "args",  orderedMap(
                            "_monitorContractId", monitorContractCid,
                            "_monitorAddress",  monitorContractAddress
                    ))),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }
            getBbcLogger().info("set monitor contract (cid: {}, address: {}) to SDP {} by tx {}",
            monitorContractCid, monitorContractAddress, config.getSdpContractName(), txHash);
            JSONObject resp = getContractState(config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Global, "");
            getBbcLogger().info("result of sdp contract state: \n{}", JSON.toJSONString(resp, SerializerFeature.PrettyFormat));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("failed to set monitor contract (cid: %s, address: %s) to SDP %s",
                    monitorContractCid, monitorContractAddress, config.getSdpContractName()
                    ), e
            );
        }
    }

    public void setSdpContractToMonitor(String sdpContractCidInString) {
        long sdpContractCid = Long.parseLong(sdpContractCidInString);
        String sdpContractAddress = String.format("0x%016X:contract", sdpContractCid);

        try {
            String txHash = sendTransaction(
                    JSON.toJSONString(orderedMap(
                    "sender", dioxideAccount.getAddressInString(),
                    "function", String.format("%s.%s.%s",config.getDappName(), config.getMonitorContractName(), "setProtocol"),
                    "args",  orderedMap(
                            "_sdpContractId", sdpContractCid,
                            "_sdpAddress",  sdpContractAddress
                    ))),
                    true
            );

            if (StrUtil.isEmpty(txHash)) {
                throw new RuntimeException("tx hash is empty");
            }
            getBbcLogger().info("set sdp contract (cid: {}, address: {}) to Monitor {} by tx {}",
            sdpContractCid, sdpContractAddress, config.getMonitorContractName(), txHash);
            JSONObject resp = getContractState(config.getDappName(), config.getMonitorContractName(), DioxideTypes.Scope.Global, "");
            getBbcLogger().info("result of monitor contract state: \n{}", JSON.toJSONString(resp, SerializerFeature.PrettyFormat));
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format("failed to set sdp contract (cid: %s, address: %s) to Monitor %s",
                    sdpContractCid, sdpContractAddress, config.getMonitorContractName()
                    ), e
            );
        }
    }

    private int[] toIntArray(byte[] bytes) {
        int[] arr = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            arr[i] = bytes[i] & 0xFF;  // 转无符号 uint8
        }
        return arr;
    }

    private void waitForContractDeployed(String txHash) {
        JSONObject state = getContractState("core","contracts", DioxideTypes.Scope.Global, "").getJSONObject("State");
        long targetHeight = -1;
        if (state != null && !state.isEmpty()) {
            JSONArray scheduledList = state.getJSONArray("Scheduled");
            if (scheduledList != null) {
                for (int i = 0; i < scheduledList.size(); i++) {
                    JSONObject s = scheduledList.getJSONObject(i);
                    if (txHash.equals(s.getString("BuildKey"))) {
                        targetHeight = s.getLongValue("TargetHeight");
                        break;
                    }
                }
            }
        }
        long curHeight = queryLatestHeight();
        while (curHeight <= targetHeight) {
            curHeight = queryLatestHeight();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SneakyThrows
    public JSONObject getContractState(String dappName, String contractName, DioxideTypes.Scope scopeType, String key) {
        LinkedHashMap<String, Object> params = orderedMap(
                "contract_with_scope", String.format("%s.%s.%s", dappName, contractName, scopeType.toString().toLowerCase())
        );
        if (scopeType != DioxideTypes.Scope.Global) {
            params.put("scope_key", key);
        }

        String rawResp = makeRequest("dx.contract_state", JSON.toJSONString(params));
        return checkIfErrorResponse(rawResp);
    }

    private boolean waitForDappDeployed(String txHash, int timeOut) {
        if (waitForTransactionConfirmed(txHash, timeOut)) {
            List<JSONObject> relays = getAllRelayTransactions(getTransactionByHash(txHash) ,true);
            for (JSONObject relay : relays) {
                if (relay.getString("Function").equals("core.coin.address.deposit")) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    private List<JSONObject> getAllRelayTransactions(DioxideTransaction tx, boolean detail) {
        List<JSONObject> res = new ArrayList<>();

        if (!isTxConfirmedWithRelays(tx)) {
            return res;
        }

        Queue<DioxideTransaction> queue = new LinkedList<>();
        queue.add(tx);
        while (!queue.isEmpty()) {
            DioxideTransaction curTx = queue.poll();
            if (curTx.getInvocation() != null && curTx.getInvocation().getRelays() != null) {
                curTx.getInvocation().getRelays().forEach(relayHash -> {
                    DioxideTransaction relayTx = getTransactionByHash(relayHash.split(":")[0]);
                    if (detail) {
                        res.add(JSON.parseObject(JSON.toJSONString(relayTx)));
                    } else {
                        res.add(JSON.parseObject(relayHash));
                    }
                    queue.add(relayTx);
                });
            }
        }
        return res;
    }

    private boolean waitForTransactionConfirmed(String txHash, int timeOut) {
        long start = System.currentTimeMillis();
        DioxideTransaction dioxideTransaction = getTransactionByHash(txHash);

        while (!isTxConfirmedWithRelays(dioxideTransaction)) {
            if (System.currentTimeMillis() - start > timeOut) {
                return false;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private boolean isTxConfirmedWithRelays(DioxideTransaction dioxideTransaction) {
        Queue<String> queue = new ArrayDeque<>();
        queue.add(dioxideTransaction.getTxHash());
        while (!queue.isEmpty()) {
            String txHash = queue.poll();
            DioxideTransaction curTx = getTransactionByHash(txHash);
            if (!isTxConfirmed(curTx)) {
                return false;
            }
            if (curTx.getInvocation() != null && curTx.getInvocation().getRelays() != null) {
                queue.addAll(
                        curTx.getInvocation().getRelays()
                                .stream()
                                .map(s -> s.split(":")[0])
                                .toList()
                );
            }
        }
        return true;
    }

    public boolean isTxConfirmed(DioxideTransaction tx) {
        if (tx == null || tx.getConfirmState() == null) {
            return false;
        }
        return DioxideTypes.TXN_CONFIRMED_STATUS.contains(tx.getConfirmState());
    }

    @SneakyThrows
    public String sendTransaction(String params, boolean sync) {
//        getBbcLogger().info("params of compose: \n{}", JSON.toJSONString(JSON.parseObject(params), SerializerFeature.PrettyFormat));
        String unsigned_txn = composeTransaction(params);
        String signed_txn = signTransaction(unsigned_txn);
        return sendRawTransaction(signed_txn, sync); // return txHash
    }

    @SneakyThrows
    private String composeTransaction(String params) {
        String rawResp = makeRequest("tx.compose", params);
        JSONObject resp = checkIfErrorResponse(rawResp);
        return resp.getString("TxData");
    }

    @SneakyThrows
    private String signTransaction(String unsigned_txn) {
        String rawResp = makeRequest("tx.sign", JSON.toJSONString(orderedMap(
                "sk", List.of(dioxideAccount.getPrivateKeyInString()),
                "txdata", unsigned_txn
        )));
        JSONObject resp = checkIfErrorResponse(rawResp);
        return resp.getString("TxData");
    }

    @SneakyThrows
    private String sendRawTransaction(String signed_txn, boolean sync) {
        String rawResp = makeRequest("tx.send", JSON.toJSONString(orderedMap("txdata", signed_txn)));
        JSONObject resp = checkIfErrorResponse(rawResp);
        String txHash = resp.getString("Hash");
        if (sync) {
            waitForTransactionConfirmed(txHash, DEFAULT_TIMEOUT);
        }
        return txHash;
    }

    private String makeRequest(String method, String params) {
        String uri = config.getRpcUrl() + "?req=" + method;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(params))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            String msg = "[makeRequest] unexpected error in method: " + method;
            getBbcLogger().error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private JSONObject checkIfErrorResponse(String rawResp) {
        if(Objects.isNull(rawResp)) {
            getBbcLogger().error("[checkIfErrorResponse] get null response from dioxide");
            throw new RuntimeException(StrUtil.format("[checkIfErrorResponse] get null response from dioxide"));
        }
        RpcResponse resp = JSON.parseObject(rawResp, RpcResponse.class);
        if (!resp.isSuccess()) {
            getBbcLogger().error("[checkIfErrorResponse] get ERROR response from dioxide: {}", JSON.toJSONString(resp));
            throw new RuntimeException(StrUtil.format("[checkIfErrorResponse] get ERROR response from dioxide: {}", JSON.toJSONString(resp)));
        }
        return resp.getSuccessResponse();
    }

    private static <K, V> LinkedHashMap<K, V> orderedMap(Object... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("Key-value pairs must be even");
        }
        int size = kvPairs.length / 2;
        LinkedHashMap<K, V> map = LinkedHashMap.newLinkedHashMap(size);

        for (int i = 0; i < kvPairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) kvPairs[i];
            @SuppressWarnings("unchecked")
            V value = (V) kvPairs[i + 1];
            map.put(key, value);
        }
        return map;
    }

    private Logger getBbcLogger() {
        return ObjectUtil.isNull(this.bbcLogger) ? NOPLogger.NOP_LOGGER : this.bbcLogger;
    }

}
