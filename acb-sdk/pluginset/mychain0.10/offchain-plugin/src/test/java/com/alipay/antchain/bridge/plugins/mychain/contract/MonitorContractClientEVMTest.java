package com.alipay.antchain.bridge.plugins.mychain.contract;

import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.plugins.mychain.sdk.Mychain010Client;
import com.alipay.mychain.sdk.domain.transaction.TransactionReceipt;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MonitorContractClientEVMTest {

    private static final String MONITOR_CONTRACT = "monitor_contract";

    private Mychain010Client mychain010Client;
    private MonitorContractClientEVM monitorContractClient;

    @Before
    public void setUp() {
        mychain010Client = mock(Mychain010Client.class);
        monitorContractClient = new MonitorContractClientEVM(
                mychain010Client,
                LoggerFactory.getLogger(getClass()));
        monitorContractClient.setContractAddress(MONITOR_CONTRACT);
        monitorContractClient.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);
    }

    @Test
    public void implementationVersionThreeShouldBeSupported() {
        TransactionReceipt receipt = mock(TransactionReceipt.class);
        byte[] encodedVersion = new byte[32];
        encodedVersion[31] = 3;

        when(receipt.getResult()).thenReturn(0L);
        when(receipt.getOutput()).thenReturn(encodedVersion);
        when(mychain010Client.localCallContract(eq(MONITOR_CONTRACT), any())).thenReturn(receipt);

        Assert.assertTrue(monitorContractClient.isImplementationVersionSupported());
    }

    @Test
    public void legacyContractWithoutVersionMethodShouldNotBeSupported() {
        when(mychain010Client.localCallContract(eq(MONITOR_CONTRACT), any()))
                .thenThrow(new RuntimeException("method not found"));

        Assert.assertFalse(monitorContractClient.isImplementationVersionSupported());
    }

    @Test
    public void resetDeploymentShouldClearAddressAndStatus() {
        monitorContractClient.resetDeployment();

        Assert.assertNull(monitorContractClient.getContractAddress());
        Assert.assertNull(monitorContractClient.getStatus());
    }
}
