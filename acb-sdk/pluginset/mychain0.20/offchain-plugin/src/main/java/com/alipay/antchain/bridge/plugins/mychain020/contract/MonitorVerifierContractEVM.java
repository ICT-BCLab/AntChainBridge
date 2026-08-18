package com.alipay.antchain.bridge.plugins.mychain020.contract;

import java.util.UUID;

import cn.hutool.core.util.StrUtil;
import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain020.exceptions.CallContractException;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Client;
import com.alipay.mychain.sdk.api.utils.Utils;
import com.alipay.mychain.sdk.common.VMTypeEnum;
import com.alipay.mychain.sdk.domain.account.Identity;
import com.alipay.mychain.sdk.vm.EVMParameter;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;

@Getter
@Setter
public class MonitorVerifierContractEVM {

    private static final String MONITOR_VERIFIER_EVM_CONTRACT_PREFIX = "MONITOR_VERIFIER_EVM_CONTRACT_";
    private static final String SET_PTC_HUB_ADDRESS_SIGN = "setPtcHubAddress(identity)";

    private final Mychain020Client mychain020Client;
    private final Logger logger;

    private String contractAddress;
    private ContractStatusEnum status;

    public MonitorVerifierContractEVM(Mychain020Client mychain020Client, Logger logger) {
        this.mychain020Client = mychain020Client;
        this.logger = logger;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public ContractStatusEnum getStatus() {
        return status;
    }

    public void setStatus(ContractStatusEnum status) {
        this.status = status;
    }

    public boolean deployContract() {
        if (StrUtil.isNotEmpty(this.contractAddress)) {
            return true;
        }

        String contractPath = MychainContractBinaryVersionEnum.selectBinaryByVersion(
                mychain020Client.getConfig().getMychainContractBinaryVersion()).getMonitorVerifierEvm();
        if (StrUtil.isEmpty(contractPath)) {
            logger.error("no monitor verifier binary code for this version of contracts");
            return false;
        }

        String contractName = MONITOR_VERIFIER_EVM_CONTRACT_PREFIX + UUID.randomUUID();
        if (mychain020Client.deployContract(contractPath, contractName, VMTypeEnum.EVM, new EVMParameter())) {
            this.contractAddress = contractName;
            this.status = ContractStatusEnum.CONTRACT_DEPLOYED;
            return true;
        }
        return false;
    }

    public void setPtcHubAddress(String ptcHubContractName) {
        setPtcHubAddress(ptcHubContractName, null);
    }

    public void setPtcHubAddress(String ptcHubContractName, Identity verifierContractIdentity) {
        EVMParameter parameters = new EVMParameter(SET_PTC_HUB_ADDRESS_SIGN);
        parameters.addIdentity(Utils.getIdentityByName(ptcHubContractName, mychain020Client.getConfig().getMychainHashType()));

        SendResponseResult result = verifierContractIdentity == null
                ? mychain020Client.callContract(this.contractAddress, parameters, true)
                : mychain020Client.callContract(verifierContractIdentity, parameters, true);
        if (!result.isSuccess()) {
            throw new CallContractException(this.contractAddress, result.getTxId(), result.getErrorMessage());
        }
        this.status = ContractStatusEnum.CONTRACT_READY;
    }

}
