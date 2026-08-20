/*
 * Copyright 2023 Ant Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alipay.antchain.bridge.relayer.core.manager.blockchain;

import java.util.HashMap;
import java.util.Map;

import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import com.alipay.antchain.bridge.relayer.commons.constant.BlockchainStateEnum;
import com.alipay.antchain.bridge.relayer.commons.model.BlockchainMeta;
import org.junit.Assert;
import org.junit.Test;

public class BlockchainManagerTest {

    @Test
    public void updateBlockchainShouldPersistMergedProperties() {
        BlockchainMeta.BlockchainProperties originalProperties = new BlockchainMeta.BlockchainProperties();
        originalProperties.setPluginServerId("ps01");
        originalProperties.setBbcContext(new DefaultBBCContext());
        originalProperties.setAnchorRuntimeStatus(BlockchainStateEnum.INIT);
        originalProperties.getExtraProperties().put("preserved_property", "preserved_value");

        BlockchainMeta originalMeta = new BlockchainMeta(
                "mychain2",
                "my02.id",
                "old_alias",
                "old_desc",
                originalProperties
        );
        TestBlockchainManager blockchainManager = new TestBlockchainManager(originalMeta);

        Map<String, String> partialConfig = new HashMap<>();
        partialConfig.put("ptc_contract_address", "ptc_contract");
        partialConfig.put("monitor_contract_address", "monitor_contract");
        partialConfig.put("monitor_verifier_contract_address", "monitor_verifier_contract");

        blockchainManager.updateBlockchain(
                "mychain2",
                "my02.id",
                "",
                "new_alias",
                "new_desc",
                partialConfig
        );

        BlockchainMeta updatedMeta = blockchainManager.updatedMeta;
        Assert.assertSame(originalMeta, updatedMeta);
        Assert.assertEquals("new_alias", updatedMeta.getAlias());
        Assert.assertEquals("new_desc", updatedMeta.getDesc());
        Assert.assertEquals("ps01", updatedMeta.getPluginServerId());
        Assert.assertNotNull(updatedMeta.getProperties().getBbcContext());
        Assert.assertEquals("ptc_contract", updatedMeta.getProperties().getPtcContractAddress());
        Assert.assertEquals("monitor_contract", updatedMeta.getProperties().getMonitorContractAddress());
        Assert.assertEquals(
                "monitor_verifier_contract",
                updatedMeta.getProperties().getExtraProperties().get("monitor_verifier_contract_address")
        );
        Assert.assertEquals(
                "preserved_value",
                updatedMeta.getProperties().getExtraProperties().get("preserved_property")
        );
    }

    private static class TestBlockchainManager extends BlockchainManager {

        private final BlockchainMeta originalMeta;

        private BlockchainMeta updatedMeta;

        private TestBlockchainManager(BlockchainMeta originalMeta) {
            this.originalMeta = originalMeta;
        }

        @Override
        public BlockchainMeta getBlockchainMeta(String product, String blockchainId) {
            return originalMeta;
        }

        @Override
        public boolean updateBlockchainMeta(BlockchainMeta blockchainMeta) {
            updatedMeta = blockchainMeta;
            return true;
        }
    }
}
