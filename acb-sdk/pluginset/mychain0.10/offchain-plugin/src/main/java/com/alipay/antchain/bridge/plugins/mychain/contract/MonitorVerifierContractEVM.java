package com.alipay.antchain.bridge.plugins.mychain.contract;

import java.util.UUID;

import cn.hutool.core.util.StrUtil;
import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain.exceptions.CallContractException;
import com.alipay.antchain.bridge.plugins.mychain.sdk.Mychain010Client;
import com.alipay.mychain.sdk.api.utils.Utils;
import com.alipay.mychain.sdk.common.VMTypeEnum;
import com.alipay.mychain.sdk.vm.EVMParameter;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;

@Getter
@Setter
public class MonitorVerifierContractEVM {

    private static final String MONITOR_VERIFIER_EVM_CONTRACT_PREFIX = "MONITOR_VERIFIER_EVM_CONTRACT_";
    private static final String SET_PTC_HUB_ADDRESS_SIGN = "setPtcHubAddress(identity)";

    private final Mychain010Client mychain010Client;
    private final Logger logger;

    private String contractAddress;
    private ContractStatusEnum status;

    public MonitorVerifierContractEVM(Mychain010Client mychain010Client, Logger logger) {
        this.mychain010Client = mychain010Client;
        this.logger = logger;
    }

    public boolean deployContract() {
        if (StrUtil.isNotEmpty(this.contractAddress)) {
            return true;
        }

        String contractPath = MychainContractBinaryVersionEnum.selectBinaryByVersion(
                mychain010Client.getConfig().getMychainContractBinaryVersion()).getMonitorVerifierEvm();
        if (StrUtil.isEmpty(contractPath)) {
            logger.error("no monitor verifier binary code for this version of contracts");
            return false;
        }

        String contractName = MONITOR_VERIFIER_EVM_CONTRACT_PREFIX + UUID.randomUUID();
        if (mychain010Client.deployContract(contractPath, contractName, VMTypeEnum.EVM, new EVMParameter())) {
            this.contractAddress = contractName;
            this.status = ContractStatusEnum.CONTRACT_DEPLOYED;
            return true;
        }
        return false;
    }

    public void setPtcHubAddress(String ptcHubContractName) {
        EVMParameter parameters = new EVMParameter(SET_PTC_HUB_ADDRESS_SIGN);
        parameters.addIdentity(Utils.getIdentityByName(ptcHubContractName, mychain010Client.getConfig().getMychainHashType()));

        SendResponseResult result = mychain010Client.callContract(this.contractAddress, parameters, true);
        if (!result.isSuccess()) {
            throw new CallContractException(this.contractAddress, result.getTxId(), result.getErrorMessage());
        }
        this.status = ContractStatusEnum.CONTRACT_READY;
    }

}
