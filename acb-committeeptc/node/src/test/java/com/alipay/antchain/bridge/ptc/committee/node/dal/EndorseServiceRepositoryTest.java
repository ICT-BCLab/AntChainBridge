/*
 * Copyright 2024 Ant Group
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

package com.alipay.antchain.bridge.ptc.committee.node.dal;

import java.math.BigInteger;
import java.security.KeyPairGenerator;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.RandomUtil;
import com.alipay.antchain.bridge.commons.bcdns.PTCCredentialSubject;
import com.alipay.antchain.bridge.commons.core.base.*;
import com.alipay.antchain.bridge.commons.core.bta.BlockchainTrustAnchorV1;
import com.alipay.antchain.bridge.commons.core.ptc.PTCTypeEnum;
import com.alipay.antchain.bridge.commons.core.ptc.ThirdPartyBlockchainTrustAnchorV1;
import com.alipay.antchain.bridge.commons.core.ptc.ValidatedConsensusStateV1;
import com.alipay.antchain.bridge.commons.utils.crypto.HashAlgoEnum;
import com.alipay.antchain.bridge.commons.utils.crypto.SignAlgoEnum;
import com.alipay.antchain.bridge.ptc.committee.node.TestBase;
import com.alipay.antchain.bridge.ptc.committee.node.commons.exception.DataAccessLayerException;
import com.alipay.antchain.bridge.ptc.committee.node.commons.models.BtaWrapper;
import com.alipay.antchain.bridge.ptc.committee.node.commons.models.TpBtaWrapper;
import com.alipay.antchain.bridge.ptc.committee.node.commons.models.ValidatedConsensusStateWrapper;
import com.alipay.antchain.bridge.ptc.committee.node.dal.repository.interfaces.IEndorseServiceRepository;
import com.alipay.antchain.bridge.ptc.committee.types.basic.CommitteeEndorseProof;
import com.alipay.antchain.bridge.ptc.committee.types.basic.CommitteeNodeProof;
import com.alipay.antchain.bridge.ptc.committee.types.basic.NodePublicKeyEntry;
import com.alipay.antchain.bridge.ptc.committee.types.tpbta.CommitteeEndorseRoot;
import com.alipay.antchain.bridge.ptc.committee.types.tpbta.NodeEndorseInfo;
import com.alipay.antchain.bridge.ptc.committee.types.tpbta.OptionalEndorsePolicy;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
public class EndorseServiceRepositoryTest extends TestBase {

    @Resource
    private IEndorseServiceRepository endorseServiceRepository;

    @SneakyThrows
    @Test
    public void testBta() {
        var oid = new ObjectIdentity(
                ObjectIdentityType.X509_PUBLIC_KEY_INFO,
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic().getEncoded()
        );
        var bta = new BlockchainTrustAnchorV1();
        bta.setBcOwnerPublicKey("test".getBytes());
        bta.setBcOwnerSig("test".getBytes());
        bta.setDomain(new CrossChainDomain("test"));
        bta.setSubjectIdentity("test".getBytes());
        bta.setBcOwnerSigAlgo(SignAlgoEnum.SHA256_WITH_ECDSA);
        bta.setPtcOid(oid);
        bta.setSubjectProduct("test");
        bta.setExtension("test".getBytes());
        bta.setSubjectVersion(0);

        var btaWrapper = new BtaWrapper();
        btaWrapper.setBta(bta);
        endorseServiceRepository.setBta(btaWrapper);
        endorseServiceRepository.setBta(btaWrapper);

        var conflictingBta = BeanUtil.copyProperties(bta, BlockchainTrustAnchorV1.class);
        conflictingBta.setSubjectIdentity("different".getBytes());
        assertConflictingWrite(() -> endorseServiceRepository.setBta(new BtaWrapper(conflictingBta)));

        var btaWrapperFromDB = endorseServiceRepository.getBta(btaWrapper.getDomain());
        Assert.assertEquals(
                HexUtil.encodeHexStr(btaWrapper.getBta().getSubjectIdentity()),
                HexUtil.encodeHexStr(btaWrapperFromDB.getBta().getSubjectIdentity())
        );
    }

    @Test
    @SneakyThrows
    public void testTpBta() {
        var policy = new OptionalEndorsePolicy();
        policy.setThreshold(new OptionalEndorsePolicy.Threshold(OptionalEndorsePolicy.OperatorEnum.GREATER_THAN, 1));
        var nodeEndorseInfo = new NodeEndorseInfo();
        nodeEndorseInfo.setNodeId("test");
        nodeEndorseInfo.setRequired(true);
        var nodePubkeyEntry = new NodePublicKeyEntry("default", SignAlgoEnum.KECCAK256_WITH_SECP256K1.getSigner().generateKeyPair().getPublic());
        nodeEndorseInfo.setPublicKey(nodePubkeyEntry);
        var crossChainLane = new CrossChainLane(new CrossChainDomain("test"), new CrossChainDomain("test"), CrossChainIdentity.fromHexStr("0000000000000000000000000000000000000000000000000000000000000001"), CrossChainIdentity.fromHexStr("0000000000000000000000000000000000000000000000000000000000000001"));
        var tpbta = new ThirdPartyBlockchainTrustAnchorV1(
                1,
                BigInteger.ONE,
                new PTCCredentialSubject(
                        "1",
                        "ptc",
                        PTCTypeEnum.COMMITTEE,
                        new X509PubkeyInfoObjectIdentity(
                                SignAlgoEnum.KECCAK256_WITH_SECP256K1.getSigner().generateKeyPair().getPublic().getEncoded()
                        ),
                        new byte[]{}
                ),
                crossChainLane,
                1,
                HashAlgoEnum.KECCAK_256,
                new CommitteeEndorseRoot(
                        "committee",
                        policy,
                        ListUtil.toList(nodeEndorseInfo)
                ).encode(),
                CommitteeEndorseProof.builder()
                        .committeeId("committee")
                        .sigs(ListUtil.toList(new CommitteeNodeProof(
                                "test",
                                SignAlgoEnum.KECCAK256_WITH_SECP256K1,
                                "".getBytes()
                        ))).build().encode()
        );
        var tpBtaWrapper = new TpBtaWrapper(tpbta);

        endorseServiceRepository.setTpBta(tpBtaWrapper);
        endorseServiceRepository.setTpBta(tpBtaWrapper);

        var conflictingTpBta = ThirdPartyBlockchainTrustAnchorV1.decode(tpbta.encode());
        conflictingTpBta.setEndorseProof("different".getBytes());
        assertConflictingWrite(() -> endorseServiceRepository.setTpBta(new TpBtaWrapper(conflictingTpBta)));

        var blockchainLevelTpBta = new ThirdPartyBlockchainTrustAnchorV1(
                2,
                BigInteger.ONE,
                tpbta.getSignerPtcCredentialSubject(),
                new CrossChainLane(crossChainLane.getSenderDomain()),
                2,
                HashAlgoEnum.KECCAK_256,
                tpbta.getEndorseRoot(),
                tpbta.getEndorseProof()
        );
        endorseServiceRepository.setTpBta(new TpBtaWrapper(blockchainLevelTpBta));

        Assert.assertNotNull(endorseServiceRepository.getMatchedTpBta(crossChainLane));
        Assert.assertEquals(
                1,
                endorseServiceRepository.getMatchedTpBta(crossChainLane, -1, 1).getTpbta().getBtaSubjectVersion()
        );
        Assert.assertTrue(endorseServiceRepository.hasTpBta(crossChainLane, 1));
        Assert.assertNotNull(endorseServiceRepository.getMatchedTpBta(crossChainLane));
    }

    @Test
    public void testValidatedConsensusState() {
        var cs = new ConsensusState(
                new CrossChainDomain("test"),
                BigInteger.valueOf(100L),
                RandomUtil.randomBytes(32),
                RandomUtil.randomBytes(32),
                System.currentTimeMillis(),
                new byte[]{},
                new byte[]{},
                new byte[]{}
        );
        var vcs = BeanUtil.copyProperties(cs, ValidatedConsensusStateV1.class);

        endorseServiceRepository.setValidatedConsensusState(new ValidatedConsensusStateWrapper(vcs));
        endorseServiceRepository.setValidatedConsensusState(new ValidatedConsensusStateWrapper(vcs));

        var conflictingVcs = BeanUtil.copyProperties(vcs, ValidatedConsensusStateV1.class);
        conflictingVcs.setHash(RandomUtil.randomBytes(32));
        assertConflictingWrite(
                () -> endorseServiceRepository.setValidatedConsensusState(new ValidatedConsensusStateWrapper(conflictingVcs))
        );

        Assert.assertTrue(endorseServiceRepository.hasValidatedConsensusState("test", BigInteger.valueOf(100L)));
        Assert.assertEquals(
                cs.getHashHex(),
                endorseServiceRepository.getValidatedConsensusState("test", BigInteger.valueOf(100L)).getValidatedConsensusState().getHashHex()
        );
        Assert.assertEquals(
                cs.getHeight(),
                endorseServiceRepository.getValidatedConsensusState("test", BigInteger.valueOf(100L)).getHeight()
        );
        Assert.assertEquals(
                cs.getParentHashHex(),
                endorseServiceRepository.getValidatedConsensusState("test", cs.getHashHex()).getParentHash()
        );
    }

    private static void assertConflictingWrite(Runnable write) {
        try {
            write.run();
            Assert.fail("conflicting payload should be rejected");
        } catch (DataAccessLayerException expected) {
            Assert.assertTrue(expected.getMessage().contains("Conflicting"));
        }
    }
}
