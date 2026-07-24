package com.alipay.antchain.bridge.plugins.mychain.contract;

import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain.sdk.Mychain010Client;
import com.alipay.antchain.bridge.plugins.mychain.sdk.Mychain010Config;
import com.alipay.mychain.sdk.api.utils.Utils;
import com.alipay.mychain.sdk.crypto.hash.HashTypeEnum;
import com.alipay.mychain.sdk.domain.transaction.TransactionReceipt;
import org.bouncycastle.util.encoders.Hex;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AMContractClientEVMTest {

    private static final String AM_CONTRACT = "am_contract";
    private static final String SDP_CONTRACT = "sdp_contract";

    @Test
    public void setProtocolShouldReuseExistingRoute() {
        Mychain010Client mychain010Client = mock(Mychain010Client.class);
        Mychain010Config config = mock(Mychain010Config.class);
        TransactionReceipt receipt = mock(TransactionReceipt.class);

        when(mychain010Client.getConfig()).thenReturn(config);
        when(config.getMychainHashType()).thenReturn(HashTypeEnum.SHA256);
        when(receipt.getOutput()).thenReturn(Hex.decode(
                Utils.getIdentityByName(SDP_CONTRACT, HashTypeEnum.SHA256).hexStrValue()));
        when(mychain010Client.localCallContract(eq(AM_CONTRACT), any())).thenReturn(receipt);

        AMContractClientEVM contract = new AMContractClientEVM(
                mychain010Client,
                LoggerFactory.getLogger(getClass()));
        contract.setContractAddress(AM_CONTRACT);

        Assert.assertTrue(contract.setProtocol(SDP_CONTRACT, "0"));
        verify(mychain010Client, never()).callContract(eq(AM_CONTRACT), any(), eq(true));
    }

    @Test
    public void setProtocolShouldWriteWhenRouteDoesNotMatch() {
        Mychain010Client mychain010Client = mock(Mychain010Client.class);
        Mychain010Config config = mock(Mychain010Config.class);
        TransactionReceipt receipt = mock(TransactionReceipt.class);
        SendResponseResult result = mock(SendResponseResult.class);

        when(mychain010Client.getConfig()).thenReturn(config);
        when(config.getMychainHashType()).thenReturn(HashTypeEnum.SHA256);
        when(receipt.getOutput()).thenReturn(new byte[32]);
        when(mychain010Client.localCallContract(eq(AM_CONTRACT), any())).thenReturn(receipt);
        when(result.isSuccess()).thenReturn(true);
        when(mychain010Client.callContract(eq(AM_CONTRACT), any(), eq(true))).thenReturn(result);

        AMContractClientEVM contract = new AMContractClientEVM(
                mychain010Client,
                LoggerFactory.getLogger(getClass()));
        contract.setContractAddress(AM_CONTRACT);

        Assert.assertTrue(contract.setProtocol(SDP_CONTRACT, "0"));
        verify(mychain010Client).callContract(eq(AM_CONTRACT), any(), eq(true));
    }
}
