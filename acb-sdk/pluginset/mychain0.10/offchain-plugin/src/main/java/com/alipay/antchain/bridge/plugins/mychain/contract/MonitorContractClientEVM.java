package com.alipay.antchain.bridge.plugins.mychain.contract;

import java.math.BigInteger;
import java.util.UUID;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain.exceptions.CallContractException;
import com.alipay.antchain.bridge.plugins.mychain.sdk.Mychain010Client;
import com.alipay.mychain.sdk.api.utils.Utils;
import com.alipay.mychain.sdk.common.VMTypeEnum;
import com.alipay.mychain.sdk.domain.transaction.TransactionReceipt;
import com.alipay.mychain.sdk.errorcode.ErrorCode;
import com.alipay.mychain.sdk.vm.EVMOutput;
import com.alipay.mychain.sdk.vm.EVMParameter;
import org.bouncycastle.util.encoders.Hex;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;

@Getter
@Setter
public class MonitorContractClientEVM {

    public static final int SUPPORTED_IMPLEMENTATION_VERSION = 3;

    private static final String MONITOR_EVM_CONTRACT_PREFIX = "MONITOR_EVM_CONTRACT_";
    private static final String GET_IMPLEMENTATION_VERSION_SIGN = "getImplementationVersion()";
    private static final String SET_SDP_ADDRESS_SIGN = "setSdpAddress(identity)";
    private static final String SET_MONITOR_VERIFIER_ADDRESS_SIGN = "setMonitorVerifierAddress(identity)";
    private static final String SET_MONITOR_CONTROL_SIGN = "setMonitorControl(uint32)";
    private static final String RECV_MONITOR_ORDER_SIGN = "recvMonitorOrder(string,string,bytes,bytes)";

    private final Mychain010Client mychain010Client;
    private final Logger logger;

    private String contractAddress;
    private ContractStatusEnum status;

    public MonitorContractClientEVM(Mychain010Client mychain010Client, Logger logger) {
        this.mychain010Client = mychain010Client;
        this.logger = logger;
    }

    public boolean deployContract() {
        if (StrUtil.isNotEmpty(this.contractAddress)) {
            return true;
        }

        String contractPath = MychainContractBinaryVersionEnum.selectBinaryByVersion(
                mychain010Client.getConfig().getMychainContractBinaryVersion()).getMonitorEvm();
        if (StrUtil.isEmpty(contractPath)) {
            logger.error("no monitor binary code for this version of contracts");
            return false;
        }

        String contractName = MONITOR_EVM_CONTRACT_PREFIX + UUID.randomUUID();
        if (mychain010Client.deployContract(contractPath, contractName, VMTypeEnum.EVM, new EVMParameter())) {
            this.contractAddress = contractName;
            this.status = ContractStatusEnum.CONTRACT_DEPLOYED;
            return true;
        }
        return false;
    }

    public boolean isImplementationVersionSupported() {
        if (StrUtil.isEmpty(this.contractAddress)) {
            return false;
        }

        try {
            TransactionReceipt receipt = mychain010Client.localCallContract(
                    this.contractAddress,
                    new EVMParameter(GET_IMPLEMENTATION_VERSION_SIGN));
            if (ObjectUtil.isEmpty(receipt)
                    || receipt.getResult() != ErrorCode.SUCCESS.getErrorCode()
                    || ObjectUtil.isEmpty(receipt.getOutput())) {
                return false;
            }

            BigInteger version = new EVMOutput(Hex.toHexString(receipt.getOutput())).getUint();
            return BigInteger.valueOf(SUPPORTED_IMPLEMENTATION_VERSION).equals(version);
        } catch (Exception e) {
            logger.info("Monitor contract {} does not expose a supported implementation version.",
                    this.contractAddress);
            return false;
        }
    }

    public void resetDeployment() {
        this.contractAddress = null;
        this.status = null;
    }

    public void setProtocol(String sdpContractName) {
        EVMParameter parameters = new EVMParameter(SET_SDP_ADDRESS_SIGN);
        parameters.addIdentity(Utils.getIdentityByName(sdpContractName, mychain010Client.getConfig().getMychainHashType()));
        call(parameters);
    }

    public void setMonitorVerifier(String monitorVerifierContractName) {
        EVMParameter parameters = new EVMParameter(SET_MONITOR_VERIFIER_ADDRESS_SIGN);
        parameters.addIdentity(Utils.getIdentityByName(monitorVerifierContractName, mychain010Client.getConfig().getMychainHashType()));
        call(parameters);
    }

    public void setMonitorControl(int monitorType) {
        EVMParameter parameters = new EVMParameter(SET_MONITOR_CONTROL_SIGN);
        parameters.addUint(BigInteger.valueOf(monitorType));
        call(parameters);
        this.status = ContractStatusEnum.CONTRACT_READY;
    }

    public SendResponseResult relayMonitorOrder(String committeeId, String signAlgo, byte[] rawProof, byte[] rawMonitorOrder) {
        EVMParameter parameters = new EVMParameter(RECV_MONITOR_ORDER_SIGN);
        parameters.addString(committeeId);
        parameters.addString(signAlgo);
        parameters.addBytes(rawProof);
        parameters.addBytes(rawMonitorOrder);

        return mychain010Client.callContract(this.contractAddress, parameters, true);
    }

    private void call(EVMParameter parameters) {
        SendResponseResult result = mychain010Client.callContract(this.contractAddress, parameters, true);
        if (!result.isSuccess()) {
            throw new CallContractException(this.contractAddress, result.getTxId(), result.getErrorMessage());
        }
    }
}
