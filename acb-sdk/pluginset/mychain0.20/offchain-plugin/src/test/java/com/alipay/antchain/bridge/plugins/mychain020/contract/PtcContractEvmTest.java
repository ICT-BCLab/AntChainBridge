package com.alipay.antchain.bridge.plugins.mychain020.contract;

import static org.mockito.Mockito.mock;

import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Client;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;

public class PtcContractEvmTest {

    @Test
    public void shouldRestoreReadyStatusForPredeployedContract() {
        PtcContractEvm contract = new PtcContractEvm(
                mock(Mychain020Client.class),
                mock(Logger.class));
        contract.setContractAddress("PTC_HUB_EVM_CONTRACT_predeployed");
        contract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);

        Assert.assertTrue(contract.deployContract("unused-for-predeployed-contract"));
        Assert.assertEquals(ContractStatusEnum.CONTRACT_READY, contract.getStatus());
    }
}
