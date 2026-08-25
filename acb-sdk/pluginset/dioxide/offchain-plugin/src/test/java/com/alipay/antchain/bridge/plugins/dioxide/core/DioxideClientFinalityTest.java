package com.alipay.antchain.bridge.plugins.dioxide.core;

import com.alipay.antchain.bridge.plugins.dioxide.conf.DioxideTypes;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class DioxideClientFinalityTest {

    @Test
    public void testIsTxFinalized() {
        Assert.assertFalse(DioxideClient.isTxFinalized(null));
        Assert.assertFalse(DioxideClient.isTxFinalized(transactionWithState(null)));
        Assert.assertFalse(DioxideClient.isTxFinalized(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_READY.name()
        )));
        Assert.assertFalse(DioxideClient.isTxFinalized(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_CONFIRMED.name()
        )));
        Assert.assertFalse(DioxideClient.isTxFinalized(transactionWithState("UNKNOWN")));
        Assert.assertTrue(DioxideClient.isTxFinalized(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_FINALIZED.name()
        )));
        Assert.assertTrue(DioxideClient.isTxFinalized(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_ARCHIVED.name()
        )));
    }

    @Test
    public void testIsTxTerminalFailed() {
        Assert.assertFalse(DioxideClient.isTxTerminalFailed(null));
        Assert.assertFalse(DioxideClient.isTxTerminalFailed(transactionWithState(null)));
        Assert.assertFalse(DioxideClient.isTxTerminalFailed(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_CONFIRMED.name()
        )));
        Assert.assertTrue(DioxideClient.isTxTerminalFailed(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_RELAY_INVALIDED.name()
        )));
        Assert.assertTrue(DioxideClient.isTxTerminalFailed(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_ABORTED.name()
        )));
        Assert.assertTrue(DioxideClient.isTxTerminalFailed(transactionWithState(
                DioxideTypes.TxnConfirmState.TXN_EXPIRED.name()
        )));
    }

    @Test
    public void testEvaluateFinalityAcrossRelayTree() {
        DioxideTransaction root = transactionWithRelays(
                "root",
                DioxideTypes.TxnConfirmState.TXN_FINALIZED.name(),
                List.of("child:0")
        );
        DioxideTransaction pendingChild = transactionWithRelays(
                "child",
                DioxideTypes.TxnConfirmState.TXN_CONFIRMED.name(),
                List.of()
        );
        DioxideTransaction finalizedChild = transactionWithRelays(
                "child",
                DioxideTypes.TxnConfirmState.TXN_ARCHIVED.name(),
                List.of()
        );
        DioxideTransaction failedChild = transactionWithRelays(
                "child",
                DioxideTypes.TxnConfirmState.TXN_ABORTED.name(),
                List.of()
        );

        Assert.assertEquals(
                DioxideClient.TxFinalityState.PENDING,
                evaluate(root, Map.of("child", pendingChild)).state()
        );
        Assert.assertEquals(
                DioxideClient.TxFinalityState.FINALIZED,
                evaluate(root, Map.of("child", finalizedChild)).state()
        );
        Assert.assertEquals(
                DioxideClient.TxFinalityState.FAILED,
                evaluate(root, Map.of("child", failedChild)).state()
        );
        Assert.assertEquals(
                DioxideClient.TxFinalityState.PENDING,
                evaluate(root, Map.of()).state()
        );
        Assert.assertEquals(
                DioxideClient.TxFinalityState.PENDING,
                evaluate(
                        transactionWithRelays(
                                "root",
                                DioxideTypes.TxnConfirmState.TXN_FINALIZED.name(),
                                List.of("")
                        ),
                        Map.of()
                ).state()
        );
    }

    @Test
    public void testRelayHashNormalizationAndCycleProtection() {
        DioxideTransaction root = transactionWithRelays(
                "root",
                DioxideTypes.TxnConfirmState.TXN_FINALIZED.name(),
                List.of("child:7")
        );
        DioxideTransaction child = transactionWithRelays(
                "child",
                DioxideTypes.TxnConfirmState.TXN_FINALIZED.name(),
                List.of("root:2")
        );

        Assert.assertEquals("child", DioxideClient.normalizeRelayTxHash("child:7"));
        Assert.assertEquals(
                DioxideClient.TxFinalityState.FINALIZED,
                evaluate(root, Map.of("root", root, "child", child)).state()
        );
    }

    private DioxideTransaction transactionWithState(String state) {
        return DioxideTransaction.builder().txHash("tx-hash").confirmState(state).build();
    }

    private DioxideTransaction transactionWithRelays(String txHash, String state, List<String> relays) {
        return DioxideTransaction.builder()
                .txHash(txHash)
                .confirmState(state)
                .invocation(DioxideTransaction.Invocation.builder().relays(relays).build())
                .build();
    }

    private DioxideClient.TxFinalityResult evaluate(
            DioxideTransaction root,
            Map<String, DioxideTransaction> transactions
    ) {
        return DioxideClient.evaluateTxFinalityWithRelays(root, transactions::get);
    }
}
