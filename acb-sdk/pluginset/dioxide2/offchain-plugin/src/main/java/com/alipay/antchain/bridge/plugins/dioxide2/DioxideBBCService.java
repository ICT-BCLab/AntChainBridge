package com.alipay.antchain.bridge.plugins.dioxide2;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.alipay.antchain.bridge.commons.bbc.AbstractBBCContext;
import com.alipay.antchain.bridge.commons.bbc.syscontract.AuthMessageContract;
import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.commons.bbc.syscontract.MonitorContract;
import com.alipay.antchain.bridge.commons.bbc.syscontract.SDPContract;
import com.alipay.antchain.bridge.commons.core.base.*;
import com.alipay.antchain.bridge.commons.core.ptc.PTCTrustRoot;
import com.alipay.antchain.bridge.commons.core.ptc.PTCTypeEnum;
import com.alipay.antchain.bridge.commons.core.ptc.PTCVerifyAnchor;
import com.alipay.antchain.bridge.commons.core.ptc.ThirdPartyBlockchainTrustAnchor;
import com.alipay.antchain.bridge.commons.core.rcc.ReliableCrossChainMessage;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideConfig;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideTypes;
import com.alipay.antchain.bridge.plugins.dioxide2.core.DioxideClient;
import com.alipay.antchain.bridge.plugins.dioxide2.core.DioxideTransaction;
import com.alipay.antchain.bridge.plugins.lib.BBCService;
import com.alipay.antchain.bridge.plugins.spi.bbc.AbstractBBCService;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

@BBCService(products = "dioxide2", pluginId = "plugin-dioxide2")
@Getter
public class DioxideBBCService extends AbstractBBCService {

    private DioxideConfig config;

    private AbstractBBCContext bbcContext;

    @Setter
    private DioxideClient dioxideClient;

    @Override
    public void startup(AbstractBBCContext abstractBBCContext) {

        getBBCLogger().info("Dioxide BBCService startup with context: {}",
                new String(abstractBBCContext.getConfForBlockchainClient()));

        if (ObjectUtil.isNull(abstractBBCContext)) {
            throw new RuntimeException("null bbc context");
        }
        if (ObjectUtil.isEmpty(abstractBBCContext.getConfForBlockchainClient())) {
            throw new RuntimeException("empty blockchain client conf");
        }

        // 1. Obtain the configuration information
        try
        {
            config = DioxideConfig.fromJsonString(new String(abstractBBCContext.getConfForBlockchainClient()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (ObjectUtil.isEmpty(config.getPrivateKey())) {
            throw new RuntimeException("private key is empty");
        }

        if (StrUtil.isEmpty(config.getRpcUrl()) || StrUtil.isEmpty(config.getWsRpc())) {
            throw new RuntimeException("dioxide url is empty");
        }

        // 6. set context
        this.bbcContext = abstractBBCContext;

        this.dioxideClient = new DioxideClient(config, getBBCLogger());

        // 7. set the pre-deployed contracts into context
        if (ObjectUtil.isNull(abstractBBCContext.getAuthMessageContract())
                && StrUtil.isNotEmpty(this.config.getAmContractAddressDeployed())) {
            AuthMessageContract authMessageContract = new AuthMessageContract();
            authMessageContract.setContractAddress(this.config.getAmContractAddressDeployed());
            authMessageContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
            this.bbcContext.setAuthMessageContract(authMessageContract);
        }

        if (ObjectUtil.isNull(abstractBBCContext.getSdpContract())
                && StrUtil.isNotEmpty(this.config.getSdpContractAddressDeployed())) {
            SDPContract sdpContract = new SDPContract();
            sdpContract.setContractAddress(this.config.getSdpContractAddressDeployed());
            sdpContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
            this.bbcContext.setSdpContract(sdpContract);
        }

        if (ObjectUtil.isNull(abstractBBCContext.getMonitorContract())
                && StrUtil.isNotEmpty(this.config.getMonitorContractAddressDeployed())) {
            MonitorContract monitorContract = new MonitorContract();
            monitorContract.setContractAddress(this.config.getMonitorContractAddressDeployed());
            monitorContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
            this.bbcContext.setMonitorContract(monitorContract);
        }

    }

    @Override
    public void shutdown() {
        getBBCLogger().info("shut down DIOXIDE BBCService!");
        dioxideClient.shutdown();
    }

    @Override
    public AbstractBBCContext getContext() {
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }

        getBBCLogger().debug("Dioxide BBCService context (amAddr: {}, amStatus: {}, sdpAddr: {}, sdpStatus: {})",
                this.bbcContext.getAuthMessageContract() != null ? this.bbcContext.getAuthMessageContract().getContractAddress() : "",
                this.bbcContext.getAuthMessageContract() != null ? this.bbcContext.getAuthMessageContract().getStatus() : "",
                this.bbcContext.getSdpContract() != null ? this.bbcContext.getSdpContract().getContractAddress() : "",
                this.bbcContext.getSdpContract() != null ? this.bbcContext.getSdpContract().getStatus() : ""
        );

        this.bbcContext.setConfForBlockchainClient(this.config.toJsonString().getBytes(StandardCharsets.UTF_8));
        return this.bbcContext;
    }

    // Relayer polls this interface after relayAuthMessage returns an unconfirmed receipt.
    @Override
    public CrossChainMessageReceipt readCrossChainMessageReceipt(String txHash) {
        DioxideTransaction dioxideTransaction = dioxideClient.getTransactionByHash(txHash);

        getBBCLogger().info("[readCrossChainMessageReceipt] tx:\n{}", JSON.toJSONString(dioxideTransaction, SerializerFeature.PrettyFormat));

        // Construct cross-chain message receipt
        CrossChainMessageReceipt crossChainMessageReceipt = dioxideClient.getCrossChainMessageReceipt(dioxideTransaction);
        getBBCLogger().info("cross chain message receipt for txhash {} : {}", txHash, JSON.toJSONString(crossChainMessageReceipt));

        return crossChainMessageReceipt;
    }

    @Override
    public List<CrossChainMessage> readCrossChainMessagesByHeight(long height) {
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (ObjectUtil.isNull(this.bbcContext.getAuthMessageContract())) {
            throw new RuntimeException("empty am contract in bbc context");
        }

        try {
            return dioxideClient.readAuthMessagesFromBlock(height);
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to readCrossChainMessagesByHeight (height: %d, contractAddr: %s, topic: %s)",
                            height,
                            "AuthMsg",
                            "SENDAUTHMESSAGE_EVENT"
                    ), e
            );
        }
    }

    @Override
    public Long queryLatestHeight() {
        return dioxideClient.queryLatestHeight();
    }

    @Override
    public void setupAuthMessageContract() {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (checkAmContractIsDeployed()) {
            // If the contract has been pre-deployed and the contract address is configured in the configuration file,
            // there is no need to redeploy.
            return;
        }

        // 2. deploy contract
        if (checkSdpContractIsDeployed() || checkMonitorContractIsDeployed()) {
            dioxideClient.getConfig().setIsPreContractDeployed(true);
        }
        long amContractCid = dioxideClient.deployAuthMsgContract();
        AuthMessageContract authMessageContract = new AuthMessageContract();
        authMessageContract.setContractAddress(String.valueOf(amContractCid));
        authMessageContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
        bbcContext.setAuthMessageContract(authMessageContract);

        getBBCLogger().info("setup am contract successful: contract_name:{}.{}",
                config.getDappName(), config.getAmContractName());
    }

    @Override
    public void setupSDPMessageContract() {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (checkSdpContractIsDeployed()) {
            // If the contract has been pre-deployed and the contract address is configured in the configuration file,
            // there is no need to redeploy.
            return;
        }

        // 2. deploy contract
        if (checkAmContractIsDeployed() || checkMonitorContractIsDeployed()) {
            dioxideClient.getConfig().setIsPreContractDeployed(true);
        }
        long sdpContractCid = dioxideClient.deploySdpContract();

        SDPContract sdpContract = new SDPContract();
        sdpContract.setContractAddress(String.valueOf(sdpContractCid));
        sdpContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
        bbcContext.setSdpContract(sdpContract);
        getBBCLogger().info("setup sdp contract successful: contract_name:{}.{}",
                config.getDappName(), config.getSdpContractName());
    }

    @Override
    public long querySDPMessageSeq(String senderDomain, String senderID, String receiverDomain, String receiverID) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (ObjectUtil.isNull(this.bbcContext.getSdpContract())) {
            throw new RuntimeException("empty sdp contract in bbc context");
        }

        return dioxideClient.querySdpSeq(senderDomain, senderID, receiverDomain, receiverID);
    }

    @Override
    public void setProtocol(String protocolAddress, String protocolType) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (ObjectUtil.isNull(this.bbcContext.getAuthMessageContract())) {
            throw new RuntimeException("empty am contract in bbc context");
        }

        // arg protocolAddress: contract cid
        dioxideClient.setProtocolToAuthMsg(protocolAddress, protocolType);

        // 4. update am contract status
        try {
            JSONObject valState = dioxideClient.getContractState(this.config.getDappName(), config.getAmContractName(), DioxideTypes.Scope.Global, "")
                    .getJSONObject("State");
            if (CollUtil.isNotEmpty(valState.getJSONObject("protocolRoutes"))
                    && StrUtil.isNotEmpty(valState.getJSONObject("protocolRoutes").getString(protocolType))) {
                this.bbcContext.getAuthMessageContract().setStatus(ContractStatusEnum.CONTRACT_READY);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to update am contract status (address: %s)",
                            this.bbcContext.getAuthMessageContract().getContractAddress()
                    ), e);
        }
    }

    @Override
    public void setAmContract(String contractAddress) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (ObjectUtil.isNull(this.bbcContext.getSdpContract())) {
            throw new RuntimeException("empty sdp contract in bbc context");
        }

        // arg contractAddress: contract cid
        dioxideClient.setAmContractToSdp(contractAddress);

        // 4. update sdp contract status
        try {
            JSONObject valState = dioxideClient.getContractState(this.config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Global, "")
                    .getJSONObject("State");
            if (!"0".equals(valState.getString("amContractId"))
                    && CollUtil.isNotEmpty(valState.getJSONArray("localDomain"))
                    && !"0".equals(valState.getString("monitorContractId"))) {
                this.bbcContext.getSdpContract().setStatus(ContractStatusEnum.CONTRACT_READY);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to update sdp contract status (address: %s)",
                            this.bbcContext.getSdpContract().getContractAddress()
                    ), e);
        }
    }

    @Override
    public void setLocalDomain(String domain) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (StrUtil.isEmpty(this.bbcContext.getSdpContract().getContractAddress())) {
            throw new RuntimeException("none sdp contract address");
        }

        dioxideClient.setLocalDomainToSdp(domain);

        // 4. update sdp contract status
        try {
            JSONObject valState = dioxideClient.getContractState(this.config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Global, "")
                    .getJSONObject("State");
            
            if (!"0".equals(valState.getString("amContractId"))
                    && CollUtil.isNotEmpty(valState.getJSONArray("localDomain"))
                    && !"0".equals(valState.getString("monitorContractId"))) {
                this.bbcContext.getSdpContract().setStatus(ContractStatusEnum.CONTRACT_READY);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to update sdp contract status (address: %s)",
                            this.bbcContext.getSdpContract().getContractAddress()
                    ), e);
        }
    }

    @Override
    public CrossChainMessageReceipt relayAuthMessage(byte[] rawMessage) {
        return relayAuthMessage(rawMessage, "");
    }

    @Override
    public CrossChainMessageReceipt relayAuthMessage(byte[] rawMessage, String submissionId) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (ObjectUtil.isNull(this.bbcContext.getAuthMessageContract())) {
            throw new RuntimeException("empty am contract in bbc context");
        }

        return dioxideClient.relayMsgToAuthMsg(rawMessage, submissionId);

    }

    @Override
    public void setupMonitorContract() {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (checkMonitorContractIsDeployed()) {
            // If the contract has been pre-deployed and the contract address is configured in the configuration file,
            // there is no need to redeploy.
            return;
        }

        // 2. deploy contract
        if (checkAmContractIsDeployed() || checkSdpContractIsDeployed()) {
            dioxideClient.getConfig().setIsPreContractDeployed(true);
        }

        long monitorContractCid = dioxideClient.deployMonitorContract();
        MonitorContract monitorContract = new MonitorContract();
        monitorContract.setContractAddress(String.valueOf(monitorContractCid));
        monitorContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
        bbcContext.setMonitorContract(monitorContract);

        getBBCLogger().info("setup monitor contract successful: contract_name:{}.{}",
                config.getDappName(), config.getMonitorContractName());
    }

    @Override
    public void setMonitorContract(String contractAddress) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (StrUtil.isEmpty(this.bbcContext.getSdpContract().getContractAddress())) {
            throw new RuntimeException("none sdp contract address");
        }

        dioxideClient.setMonitorContractToSdp(contractAddress);

        // 4. update sdp contract status
        try {
            JSONObject valState = dioxideClient.getContractState(this.config.getDappName(), config.getSdpContractName(), DioxideTypes.Scope.Global, "")
                    .getJSONObject("State");

            System.out.println("amContractId: " + valState.getString("amContractId"));
            System.out.println("amContractId(bool): " + !"0".equals(valState.getString("amContractId")));
            System.out.println("localDomain: " + valState.getJSONArray("localDomain"));
            System.out.println("localDomain(bool): " + CollUtil.isNotEmpty(valState.getJSONArray("localDomain")));
            System.out.println("monitorContractId: " + valState.getString("monitorContractId"));
            System.out.println("monitorContractId(bool): " + !"0".equals(valState.getString("monitorContractId")));
            
            if (!"0".equals(valState.getString("amContractId"))
                    && CollUtil.isNotEmpty(valState.getJSONArray("localDomain"))
                    && !"0".equals(valState.getString("monitorContractId"))) {
                this.bbcContext.getSdpContract().setStatus(ContractStatusEnum.CONTRACT_READY);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to update sdp contract status (address: %s)",
                            this.bbcContext.getSdpContract().getContractAddress()
                    ), e);
        }
    }

    @Override
    public void setProtocolInMonitor(String contractAddress) {
        // 1. check context
        if (ObjectUtil.isNull(this.bbcContext)) {
            throw new RuntimeException("empty bbc context");
        }
        if (StrUtil.isEmpty(this.bbcContext.getMonitorContract().getContractAddress())) {
            throw new RuntimeException("none monitor contract address");
        }

        dioxideClient.setSdpContractToMonitor(contractAddress);

        // 4. update monitor contract status
        try {
            JSONObject valState = dioxideClient.getContractState(this.config.getDappName(), config.getMonitorContractName(), DioxideTypes.Scope.Global, "")
                    .getJSONObject("State");
            if (!"0".equals(valState.getString("sdpContractId"))) {
                this.bbcContext.getMonitorContract().setStatus(ContractStatusEnum.CONTRACT_READY);
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    String.format(
                            "failed to update monitor contract status (address: %s)",
                            this.bbcContext.getMonitorContract().getContractAddress()
                    ), e);
        }
    }

    @Override
    public void setMonitorControl(int monitorType) {

    }

    @Override
    public void setPtcHubInMonitorVerifier(String contractAddress) {

    }

    @Override
    public CrossChainMessageReceipt relayMonitorOrder(String committeeId, String signAlgo, byte[] rawProof, byte[] rawMonitorOrder) {
        return null;
    }

    @Override
    public ConsensusState readConsensusState(BigInteger height) {
        return null;
    }

    @Override
    public boolean hasTpBta(CrossChainLane tpbtaLane, int tpBtaVersion) {
        return false;
    }

    @Override
    public ThirdPartyBlockchainTrustAnchor getTpBta(CrossChainLane tpbtaLane, int tpBtaVersion) {
        return null;
    }

    @Override
    public Set<PTCTypeEnum> getSupportedPTCType() {
        return null;
    }

    @Override
    public PTCTrustRoot getPTCTrustRoot(@NonNull ObjectIdentity ptcOwnerOid) {
        return null;
    }

    @Override
    public boolean hasPTCTrustRoot(ObjectIdentity ptcOwnerOid) {
        return false;
    }

    @Override
    public PTCVerifyAnchor getPTCVerifyAnchor(ObjectIdentity ptcOwnerOid, BigInteger version) {
        return null;
    }

    @Override
    public boolean hasPTCVerifyAnchor(ObjectIdentity ptcOwnerOid, BigInteger version) {
        return false;
    }

    @Override
    public void setupPTCContract() {

    }

    @Override
    public void setPtcContract(String ptcContractAddress) {

    }

    @Override
    public void addTpBta(ThirdPartyBlockchainTrustAnchor tpbta) {

    }

    @Override
    public BlockState queryValidatedBlockStateByDomain(CrossChainDomain recvDomain) {
        return null;
    }

    @Override
    public CrossChainMessageReceipt recvOffChainException(String exceptionMsgAuthor, byte[] exceptionMsgPkg) {
        return null;
    }

    @Override
    public CrossChainMessageReceipt reliableRetry(ReliableCrossChainMessage msg) {
        throw new UnsupportedOperationException("not supported");
    }

    private boolean checkAmContractIsDeployed() {
        return ObjectUtil.isNotNull(this.bbcContext.getAuthMessageContract())
        && StrUtil.isNotEmpty(this.bbcContext.getAuthMessageContract().getContractAddress())
        && dioxideClient.isContractVersionCurrent(
                config.getAmContractName(),
                this.bbcContext.getAuthMessageContract().getContractAddress()
        );
    }

    private boolean checkSdpContractIsDeployed() {
        return ObjectUtil.isNotNull(this.bbcContext.getSdpContract())
        && StrUtil.isNotEmpty(this.bbcContext.getSdpContract().getContractAddress())
        && dioxideClient.isContractVersionCurrent(
                config.getSdpContractName(),
                this.bbcContext.getSdpContract().getContractAddress()
        );
    }

    private boolean checkMonitorContractIsDeployed() {
        return ObjectUtil.isNotNull(this.bbcContext.getMonitorContract())
        && StrUtil.isNotEmpty(this.bbcContext.getMonitorContract().getContractAddress())
        && dioxideClient.isContractVersionCurrent(
                config.getMonitorContractName(),
                this.bbcContext.getMonitorContract().getContractAddress()
        );
    }

    private boolean isByteArrayZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0x00) {
                return false;
            }
        }
        return true;
    }

}
