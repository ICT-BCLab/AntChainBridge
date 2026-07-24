package com.alipay.antchain.bridge.plugins.mychain;

import cn.hutool.core.util.ReflectUtil;
import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import com.alipay.antchain.bridge.commons.bbc.syscontract.ContractStatusEnum;
import com.alipay.antchain.bridge.commons.bbc.syscontract.MonitorContract;
import com.alipay.antchain.bridge.plugins.mychain.sdk.Mychain010Client;
import org.junit.Assert;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.mock;

public class Mychain010BBCContextTest {

    @Test
    public void initMonitorContractShouldRestorePersistedMonitorAddress() {
        MonitorContract persistedMonitorContract = new MonitorContract();
        persistedMonitorContract.setContractAddress("upgraded_monitor_contract");
        persistedMonitorContract.setStatus(ContractStatusEnum.CONTRACT_DEPLOYED);

        DefaultBBCContext persistedContext = new DefaultBBCContext();
        persistedContext.setMonitorContract(persistedMonitorContract);

        Mychain010BBCContext context = new Mychain010BBCContext(
                persistedContext,
                LoggerFactory.getLogger(getClass()));
        ReflectUtil.invoke(context, "initMonitorContract", mock(Mychain010Client.class));

        Assert.assertEquals(
                "upgraded_monitor_contract",
                context.getMonitorContractClientEVM().getContractAddress());
        Assert.assertEquals(
                ContractStatusEnum.CONTRACT_DEPLOYED,
                context.getMonitorContractClientEVM().getStatus());
    }
}
