package com.alipay.antchain.bridge.relayer.core.service.process;

import java.lang.reflect.Field;

import com.alipay.antchain.bridge.relayer.commons.model.BlockchainMeta;
import com.alipay.antchain.bridge.relayer.core.manager.blockchain.IBlockchainManager;
import com.alipay.antchain.bridge.relayer.core.manager.network.IRelayerNetworkManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ProcessServiceRouteTest {
    private final ProcessService service = new ProcessService();
    private final IBlockchainManager blockchains = mock(IBlockchainManager.class);
    private final IRelayerNetworkManager network = mock(IRelayerNetworkManager.class);

    @Before
    public void injectManagers() throws ReflectiveOperationException {
        inject("blockchainManager", blockchains);
        inject("relayerNetworkManager", network);
    }

    @Test
    public void waitingMessageCanResumeOnceLocalAnchorStarts() {
        BlockchainMeta local = mock(BlockchainMeta.class);
        when(blockchains.getBlockchainMetaByDomain("idea20")).thenReturn(local);
        when(local.isRunning()).thenReturn(false, true);

        assertFalse(service.isRouteReady("my03", "idea20"));
        assertTrue(service.isRouteReady("my03", "idea20"));
        verifyNoInteractions(network);
    }

    @Test
    public void remoteRouteAlsoRequiresAnEstablishedChannel() {
        assertFalse(service.isRouteReady("my03", "idea20"));
        when(network.findRemoteRelayer("idea20")).thenReturn("remote");
        assertFalse(service.isRouteReady("my03", "idea20"));
        when(network.hasCrossChainChannel("my03", "idea20")).thenReturn(true);
        assertTrue(service.isRouteReady("my03", "idea20"));
    }

    private void inject(String name, Object value) throws ReflectiveOperationException {
        Field field = ProcessService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
