package org.antchainbridge.experiment.fisco;

import java.nio.file.Files;
import java.nio.file.Path;

import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import org.antchainbridge.experiment.ExperimentConfig;
import org.antchainbridge.experiment.OpenLoopWorkload;
import org.antchainbridge.experiment.TransactionSender;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;

public final class FiscoExperimentSender implements TransactionSender {

    private final FiscoMessageSender messageSender;
    private final Runnable closeAction;
    private final String targetDomain;
    private final byte[] targetApp;

    private FiscoExperimentSender(ExperimentConfig config) throws Exception {
        DefaultBBCContext context = new DefaultBBCContext();
        context.setConfForBlockchainClient(Files.readAllBytes(Path.of(config.required("chain.config"))));
        FiscoVariant variant = requireSourceProduct(config.required("source.product"));
        String sourceApp = normaliseAddress(config.required("source.app"));
        if (variant == FiscoVariant.BASE) {
            com.alipay.antchain.bridge.plugins.fiscobcos.FISCOBCOSBBCService service =
                    new com.alipay.antchain.bridge.plugins.fiscobcos.FISCOBCOSBBCService();
            service.startup(context);
            com.alipay.antchain.bridge.plugins.fiscobcos.abi.AppContract app =
                    com.alipay.antchain.bridge.plugins.fiscobcos.abi.AppContract.load(
                            sourceApp, service.getClient(), service.getKeyPair());
            this.messageSender = app::sendUnorderedMessage;
            this.closeAction = service::shutdown;
        } else {
            com.alipay.antchain.bridge.plugins.fiscobcos3.FISCOBCOSBBCService service =
                    new com.alipay.antchain.bridge.plugins.fiscobcos3.FISCOBCOSBBCService();
            service.startup(context);
            com.alipay.antchain.bridge.plugins.fiscobcos3.abi.AppContract app =
                    com.alipay.antchain.bridge.plugins.fiscobcos3.abi.AppContract.load(
                            sourceApp, service.getClient(), service.getKeyPair());
            this.messageSender = app::sendUnorderedMessage;
            this.closeAction = service::shutdown;
        }
        this.targetDomain = config.required("target.domain");
        this.targetApp = parseCrossChainId(config.required("target.app"));
    }

    @Override
    public String send(byte[] payload) {
        TransactionReceipt receipt = messageSender.send(targetDomain, targetApp, payload);
        if (receipt == null || !receipt.isStatusOK()) {
            throw new IllegalStateException(
                    "FISCO source transaction failed: " + (receipt == null ? "no receipt" : receipt.getStatus())
            );
        }
        return receipt.getTransactionHash();
    }

    @Override
    public void close() {
        closeAction.run();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: FiscoExperimentSender EXPERIMENT_PROPERTIES");
        }
        ExperimentConfig config = ExperimentConfig.load(Path.of(args[0]));
        try (FiscoExperimentSender sender = new FiscoExperimentSender(config)) {
            new OpenLoopWorkload().run(config, sender);
        }
    }

    private static String normaliseAddress(String value) {
        String raw = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if (!raw.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("address must be a 20-byte hexadecimal value");
        }
        return "0x" + raw.toLowerCase();
    }

    private static byte[] parseCrossChainId(String value) {
        String raw = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if (!raw.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("target.app must be a 20-byte address or 32-byte identity");
        }
        byte[] result = new byte[32];
        int offset = raw.length() == 40 ? 12 : 0;
        for (int i = 0; i < raw.length() / 2; i++) {
            result[offset + i] = (byte) Integer.parseInt(raw.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    static FiscoVariant requireSourceProduct(String product) {
        if ("fiscobcos".equals(product)) {
            return FiscoVariant.BASE;
        }
        if ("fiscobcos3".equals(product)) {
            return FiscoVariant.XUN;
        }
        throw new IllegalArgumentException(
                "source.product must be fiscobcos (Base) or fiscobcos3 (Xun)");
    }

    enum FiscoVariant {
        BASE,
        XUN
    }

    @FunctionalInterface
    private interface FiscoMessageSender {
        TransactionReceipt send(String receiverDomain, byte[] receiverIdentity, byte[] payload);
    }
}
