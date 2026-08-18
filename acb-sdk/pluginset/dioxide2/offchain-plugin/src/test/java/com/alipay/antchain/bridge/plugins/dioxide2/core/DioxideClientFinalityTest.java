package com.alipay.antchain.bridge.plugins.dioxide2.core;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alipay.antchain.bridge.plugins.dioxide2.conf.DioxideTypes;
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

    @Test
    public void testFinalizedRelayGroupReportsEmbeddedInvocationFailure() {
        JSONObject relayGroup = relayGroup(
                DioxideTypes.TxnConfirmState.TXN_ARCHIVED.name(),
                embeddedInvocation("AuthMsg.global.__relaylambda_8", "IVKRET_SUCCESS", List.of()),
                embeddedInvocation("AuthMsg.global.__relaylambda_9", "IVKRET_EXCEPTION_THROWN", List.of())
        );

        DioxideClient.RelayEventInspection inspection = DioxideClient.inspectRelayEvents(relayGroup, "relay-group");

        Assert.assertEquals(DioxideClient.RelayEventState.FAILED, inspection.state());
        Assert.assertTrue(inspection.eventTargetTxHashes().isEmpty());
        Assert.assertTrue(inspection.errorMessage().contains("IVKRET_EXCEPTION_THROWN"));
        Assert.assertTrue(inspection.errorMessage().contains("AuthMsg.global.__relaylambda_9"));
    }

    @Test
    public void testFinalizedRelayGroupReturnsProtocolEventTransactions() {
        JSONObject relayGroup = relayGroup(
                DioxideTypes.TxnConfirmState.TXN_ARCHIVED.name(),
                embeddedInvocation("AuthMsg.global.__relaylambda_8", "IVKRET_SUCCESS", List.of()),
                embeddedInvocation(
                        "AuthMsg.global.__relaylambda_9",
                        "IVKRET_SUCCESS",
                        List.of("target-a:0", "target-b", "target-a:1")
                )
        );

        DioxideClient.RelayEventInspection inspection = DioxideClient.inspectRelayEvents(relayGroup, "relay-group");

        Assert.assertEquals(DioxideClient.RelayEventState.READY, inspection.state());
        Assert.assertEquals(List.of("target-a", "target-b"), inspection.eventTargetTxHashes());
        Assert.assertEquals("", inspection.errorMessage());
    }

    @Test
    public void testMissingRelayEventsRemainPendingOnlyBeforeFinality() {
        Assert.assertEquals(
                DioxideClient.RelayEventState.PENDING,
                DioxideClient.inspectRelayEvents(
                        relayGroup(DioxideTypes.TxnConfirmState.TXN_CONFIRMED.name()),
                        "relay-group"
                ).state()
        );
        Assert.assertEquals(
                DioxideClient.RelayEventState.FAILED,
                DioxideClient.inspectRelayEvents(
                        relayGroup(DioxideTypes.TxnConfirmState.TXN_ARCHIVED.name()),
                        "relay-group"
                ).state()
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

    private JSONObject relayGroup(String confirmState, JSONObject... embeddedInvocations) {
        JSONObject relayGroup = new JSONObject();
        relayGroup.put("Hash", "relay-group");
        relayGroup.put("ConfirmState", confirmState);
        JSONArray relays = new JSONArray();
        relays.addAll(List.of(embeddedInvocations));
        relayGroup.put("Relays", relays);
        return relayGroup;
    }

    private JSONObject embeddedInvocation(String function, String status, List<String> relays) {
        JSONObject invocation = new JSONObject();
        invocation.put("Status", status);
        invocation.put("Relays", relays);
        JSONObject embedded = new JSONObject();
        embedded.put("Function", function);
        embedded.put("Invocation", invocation);
        return embedded;
    }
}
