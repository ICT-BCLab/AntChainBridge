package com.alipay.antchain.bridge.plugins.ethereum2.core;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson.JSONObject;
import lombok.*;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.infrastructure.json.JsonUtil;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.altair.SyncAggregate;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.versions.altair.SyncAggregateSchema;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EthConsensusEndorsements {

    public static EthConsensusEndorsements fromJson(String json, int syncCommitteeSize) {
        try {
            JSONObject jsonObject = JSONObject.parseObject(json);
            return new EthConsensusEndorsements(
                    JsonUtil.parse(jsonObject.getString("sync_aggregate"), SyncAggregateSchema.create(syncCommitteeSize).getJsonTypeDefinition()),
                    jsonObject.containsKey("signature_slot") ? UInt64.valueOf(jsonObject.getString("signature_slot")) : null
            );
        } catch (Exception e) {
            throw new RuntimeException("failed to parse EthConsensusEndorsements from json: ", e);
        }
    }

    private SyncAggregate syncAggregate;

    private UInt64 signatureSlot;

    public EthConsensusEndorsements(SyncAggregate syncAggregate) {
        this.syncAggregate = syncAggregate;
    }

    public UInt64 getSignatureSlotOrDefault(UInt64 fallback) {
        return ObjectUtil.defaultIfNull(signatureSlot, fallback);
    }

    @SneakyThrows
    public String toJson() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("sync_aggregate", JsonUtil.serialize(syncAggregate, syncAggregate.getSchema().getJsonTypeDefinition()));
        if (ObjectUtil.isNotNull(signatureSlot)) {
            jsonObject.put("signature_slot", signatureSlot.toString());
        }
        return jsonObject.toJSONString();
    }
}
