package com.alipay.antchain.bridge.plugins.ethereum2;

import java.nio.charset.StandardCharsets;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.antchain.bridge.plugins.ethereum2.core.EthConsensusStateData;
import com.alipay.antchain.bridge.plugins.ethereum2.core.InvalidConsensusDataException;
import com.alipay.antchain.bridge.plugins.ethereum2.core.eth.LightClientUpdateWrapper;
import com.alipay.antchain.bridge.plugins.ethereum2.core.eth.eth2spec.Eth2ChainConfig;
import lombok.SneakyThrows;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.spec.datastructures.lightclient.LightClientUpdate;
import tech.pegasys.teku.spec.datastructures.lightclient.LightClientUpdateSchema;
import tech.pegasys.teku.spec.datastructures.state.SyncCommittee;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class EthConsensusStateDataTest {

    private static final Eth2ChainConfig CHAIN_CONFIG = Eth2ChainConfig.MAINNET_CHAIN_CONFIG;

    private static final SyncCommittee CURRENT_SYNC_COMMITTEE = new LightClientUpdateWrapper(
            parseLightClientUpdate(loadUpdate("update.json"))
    ).getNextSyncCommittee();

    @Test
    public void testValidateApplicableLightClientUpdate() {
        var stateData = createStateData(loadUpdate("update-next-period.json"));

        stateData.validateLightClientUpdate(CURRENT_SYNC_COMMITTEE, CHAIN_CONFIG);
    }

    @Test
    public void testRejectLightClientUpdateWithoutSuperMajority() {
        var update = loadUpdate("update-next-period.json");
        var syncAggregate = update.getJSONObject("sync_aggregate");
        var bits = syncAggregate.getString("sync_committee_bits");
        var byteCount = (bits.length() - 2) / 2;
        syncAggregate.put("sync_committee_bits", "0x01" + "00".repeat(byteCount - 1));

        var exception = assertThrows(
                InvalidConsensusDataException.class,
                () -> createStateData(update).validateLightClientUpdate(CURRENT_SYNC_COMMITTEE, CHAIN_CONFIG)
        );

        assertEquals("sync committee signature is not enough", exception.getMessage());
    }

    @Test
    public void testRejectLightClientUpdateWithInvalidFinalityBranch() {
        var update = loadUpdate("update-next-period.json");
        var finalityBranch = update.getJSONArray("finality_branch");
        for (int i = 0; i < finalityBranch.size(); i++) {
            finalityBranch.set(i, Bytes32.ZERO.toHexString());
        }

        var exception = assertThrows(
                InvalidConsensusDataException.class,
                () -> createStateData(update).validateLightClientUpdate(CURRENT_SYNC_COMMITTEE, CHAIN_CONFIG)
        );

        assertEquals("finalized header merkle proof is invalid", exception.getMessage());
    }

    @Test
    public void testRejectLightClientUpdateWithInvalidSlotOrder() {
        var update = loadUpdate("update-next-period.json");
        update.put(
                "signature_slot",
                update.getJSONObject("attested_header").getJSONObject("beacon").getString("slot")
        );

        var exception = assertThrows(
                InvalidConsensusDataException.class,
                () -> createStateData(update).validateLightClientUpdate(CURRENT_SYNC_COMMITTEE, CHAIN_CONFIG)
        );

        assertEquals("signature slot must be greater than attested header slot", exception.getMessage());
    }

    @Test
    public void testRejectNextCommitteeWithoutSamePeriodFinality() {
        var update = loadUpdate("update-next-period.json");
        update.getJSONObject("finalized_header").getJSONObject("beacon").put("slot", "0");

        var exception = assertThrows(
                InvalidConsensusDataException.class,
                () -> createStateData(update).validateLightClientUpdate(CURRENT_SYNC_COMMITTEE, CHAIN_CONFIG)
        );

        assertEquals("finalized header period is not equal to attested header period", exception.getMessage());
    }

    private static EthConsensusStateData createStateData(JSONObject updateJson) {
        var update = new LightClientUpdateWrapper(parseLightClientUpdate(updateJson));
        var stateData = new EthConsensusStateData();
        stateData.setBeaconBlockHeader(update.getAttestedHeader().getBeacon());
        stateData.setLightClientUpdateWrapper(update);
        return stateData;
    }

    @SneakyThrows
    private static LightClientUpdate parseLightClientUpdate(JSONObject updateJson) {
        return JsonUtil.parse(
                updateJson.toJSONString(),
                new LightClientUpdateSchema(CHAIN_CONFIG.getSpecConfig()).getJsonTypeDefinition()
        );
    }

    private static JSONObject loadUpdate(String filename) {
        return JSON.parseObject(FileUtil.readString(filename, StandardCharsets.UTF_8));
    }
}
