package com.alipay.antchain.bridge.relayer.core.manager.bcdns;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import com.alipay.antchain.bridge.commons.bcdns.AbstractCrossChainCertificate;
import com.alipay.antchain.bridge.commons.bcdns.CrossChainCertificateFactory;
import com.alipay.antchain.bridge.commons.core.base.CrossChainDomain;
import com.alipay.antchain.bridge.commons.core.base.X509PubkeyInfoObjectIdentity;
import com.alipay.antchain.bridge.relayer.commons.constant.DomainCertApplicationStateEnum;
import com.alipay.antchain.bridge.relayer.commons.exception.AntChainBridgeRelayerException;
import com.alipay.antchain.bridge.relayer.commons.model.DomainCertWrapper;
import com.alipay.antchain.bridge.relayer.dal.repository.IBCDNSRepository;
import com.alipay.antchain.bridge.relayer.dal.repository.IBlockchainRepository;
import org.junit.Assert;
import org.junit.Test;

public class BCDNSManagerApplicationResultTest {
    private static final String DOMAIN = "application.test";

    private AbstractCrossChainCertificate certificate(String domain, String id) {
        AbstractCrossChainCertificate cert = CrossChainCertificateFactory.createDomainNameCertificateSigningRequest(
                "1", new CrossChainDomain(""), new CrossChainDomain(domain),
                new X509PubkeyInfoObjectIdentity(new byte[]{1, 2, 3}), new byte[0]);
        cert.setId(id);
        return cert;
    }

    private static class Fixture {
        boolean applicationExists = true;
        DomainCertWrapper saved;
        int inserts;
        List<DomainCertApplicationStateEnum> states = new ArrayList<>();
        final BCDNSManager manager = new BCDNSManager();

        Fixture() throws Exception {
            IBCDNSRepository applications = (IBCDNSRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{IBCDNSRepository.class}, (proxy, method, args) -> {
                        if ("hasDomainCertApplicationEntry".equals(method.getName())) return applicationExists;
                        if ("updateDomainCertApplicationState".equals(method.getName())) {
                            states.add((DomainCertApplicationStateEnum) args[1]); return null;
                        }
                        throw new AssertionError(method.getName());
                    });
            IBlockchainRepository certificates = (IBlockchainRepository) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{IBlockchainRepository.class}, (proxy, method, args) -> {
                        if ("hasDomainCert".equals(method.getName())) return saved != null;
                        if ("getDomainCert".equals(method.getName())) return saved;
                        if ("saveDomainCert".equals(method.getName())) {
                            saved = (DomainCertWrapper) args[0]; inserts++; return null;
                        }
                        throw new AssertionError(method.getName());
                    });
            set("bcdnsRepository", applications);
            set("blockchainRepository", certificates);
        }

        void set(String name, Object value) throws Exception {
            Field field = BCDNSManager.class.getDeclaredField(name);
            field.setAccessible(true); field.set(manager, value);
        }
    }

    @Test
    public void sameCertificateAlreadyFetchedCompletesApplicationWithoutReinserting() throws Exception {
        Fixture f = new Fixture();
        AbstractCrossChainCertificate cert = certificate(DOMAIN, "same");
        f.saved = new DomainCertWrapper(cert);
        f.manager.saveDomainCertApplicationResult(DOMAIN, cert);
        Assert.assertEquals(0, f.inserts);
        Assert.assertEquals(DomainCertApplicationStateEnum.APPLY_SUCCESS, f.states.get(0));
    }

    @Test
    public void differentCertificateNeverOverwritesExistingIdentityOrApplication() throws Exception {
        Fixture f = new Fixture();
        f.saved = new DomainCertWrapper(certificate(DOMAIN, "original"));
        Assert.assertThrows(AntChainBridgeRelayerException.class,
                () -> f.manager.saveDomainCertApplicationResult(DOMAIN, certificate(DOMAIN, "different")));
        Assert.assertTrue(f.states.isEmpty()); Assert.assertEquals(0, f.inserts);
        Assert.assertEquals("original", f.saved.getCrossChainCertificate().getId());
    }

    @Test
    public void certificateForAnotherDomainDoesNotCompleteThisApplication() throws Exception {
        Fixture f = new Fixture();
        Assert.assertThrows(AntChainBridgeRelayerException.class,
                () -> f.manager.saveDomainCertApplicationResult(DOMAIN, certificate("other.test", "wrong")));
        Assert.assertTrue(f.states.isEmpty()); Assert.assertEquals(0, f.inserts);
    }

    @Test
    public void newCertificateIsSavedOnceAndReplayedCallbackIsIdempotent() throws Exception {
        Fixture f = new Fixture(); AbstractCrossChainCertificate cert = certificate(DOMAIN, "new");
        f.manager.saveDomainCertApplicationResult(DOMAIN, cert);
        f.manager.saveDomainCertApplicationResult(DOMAIN, cert);
        Assert.assertEquals(1, f.inserts);
        Assert.assertEquals(2, f.states.size());
        Assert.assertEquals(DomainCertApplicationStateEnum.APPLY_SUCCESS, f.states.get(1));
    }

    @Test
    public void unsuccessfulApplicationDoesNotCreateCertificate() throws Exception {
        Fixture f = new Fixture(); f.manager.saveDomainCertApplicationResult(DOMAIN, null);
        Assert.assertEquals(0, f.inserts);
        Assert.assertEquals(DomainCertApplicationStateEnum.APPLY_FAILED, f.states.get(0));
    }
}
