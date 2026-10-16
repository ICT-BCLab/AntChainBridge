package com.alipay.antchain.bridge.relayer.core.service.validation;

import java.lang.reflect.Field;

import com.alipay.antchain.bridge.commons.core.base.CrossChainDomain;
import com.alipay.antchain.bridge.commons.core.base.CrossChainMessage;
import com.alipay.antchain.bridge.commons.core.base.UniformCrosschainPacket;
import com.alipay.antchain.bridge.relayer.commons.constant.AuthMsgProcessStateEnum;
import com.alipay.antchain.bridge.relayer.commons.model.UniformCrosschainPacketContext;
import com.alipay.antchain.bridge.relayer.dal.repository.ICrossChainMessageRepository;
import com.alipay.antchain.bridge.relayer.dal.repository.impl.BlockchainIdleDCache;
import org.junit.Before;
import org.junit.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ValidationWakeupTest {
    final ValidationService service = new ValidationService();
    private final ICrossChainMessageRepository repository = mock(ICrossChainMessageRepository.class);
    private final UniformCrosschainPacketValidator validator = mock(UniformCrosschainPacketValidator.class);
    private final BlockchainIdleDCache cache = mock(BlockchainIdleDCache.class);
    private final TestTransactions manager = new TestTransactions();
    private final UniformCrosschainPacketContext context = new UniformCrosschainPacketContext();

    @Before
    public void setUp() throws Exception {
        context.setUcpId("test-ucp");
        context.setProduct("mychain020");
        context.setBlockchainId("source-chain");
        context.setUcp(new UniformCrosschainPacket(new CrossChainDomain("source"),
                CrossChainMessage.createCrossChainMessage(CrossChainMessage.CrossChainMessageType.AUTH_MSG,
                        1, 1, new byte[32], new byte[0], new byte[0], new byte[0], new byte[32]), null));
        when(repository.getUniformCrosschainPacket("test-ucp", true)).thenReturn(context);
        when(repository.getAuthMessageState("test-ucp")).thenReturn(AuthMsgProcessStateEnum.PROVED);
        inject(service, "crossChainMessageRepository", repository);
        inject(service, "uniformCrosschainPacketValidator", validator);
        inject(service, "blockchainIdleDCache", cache);
        inject(service, "transactionTemplate", new TransactionTemplate(manager));
    }

    @Test
    public void wakesOnlyAfterSuccessfulCommit() {
        doAnswer(call -> { verifyNoInteractions(cache); return null; }).when(validator).doProcess(context);
        doAnswer(call -> { assertTrue(manager.committed); return null; }).when(cache)
                .setLastAMReceiveTime("mychain020", "source-chain");
        service.wrapUCPTask("test-ucp").run();
        verify(cache).setLastAMReceiveTime("mychain020", "source-chain");
        assertTrue(manager.committed);
    }

    @Test
    public void failedValidationRollsBackWithoutWaking() {
        doThrow(new IllegalStateException("rejected proof")).when(validator).doProcess(context);
        try { service.wrapUCPTask("test-ucp").run(); fail("expected validation failure"); }
        catch (RuntimeException expected) { assertTrue(manager.rolledBack); }
        verifyNoInteractions(cache);
        assertFalse(manager.committed);
    }

    @Test
    public void failedCommitDoesNotWake() {
        manager.failCommit = true;
        try { service.wrapUCPTask("test-ucp").run(); fail("expected commit failure"); }
        catch (IllegalStateException expected) { assertFalse(manager.committed); }
        verifyNoInteractions(cache);
    }

    @Test
    public void rejectedOrNotReadyMessageDoesNotWake() {
        when(repository.getAuthMessageState("test-ucp"))
                .thenReturn(AuthMsgProcessStateEnum.REJECTED, AuthMsgProcessStateEnum.NOT_READY);
        service.wrapUCPTask("test-ucp").run();
        service.wrapUCPTask("test-ucp").run();
        verifyNoInteractions(cache);
    }

    @Test
    public void missingMessageDoesNotWake() {
        when(repository.getUniformCrosschainPacket("test-ucp", true)).thenReturn(null);
        service.wrapUCPTask("test-ucp").run();
        verifyNoInteractions(cache, validator);
    }

    @Test
    public void cacheFailureDoesNotRetryCommittedProof() {
        doThrow(new IllegalStateException("cache unavailable")).when(cache)
                .setLastAMReceiveTime("mychain020", "source-chain");
        service.wrapUCPTask("test-ucp").run();
        assertTrue(manager.committed);
        assertFalse(manager.rolledBack);
        verify(validator, times(1)).doProcess(context);
    }

    static void inject(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static class TestTransactions extends AbstractPlatformTransactionManager {
        boolean committed, rolledBack, failCommit;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) { throw new IllegalStateException("commit failed"); }
            committed = true;
        }
        protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; }
    }
}
