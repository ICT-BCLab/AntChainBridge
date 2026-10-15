package org.antchainbridge.experiment.ethereum;

import java.nio.file.Files;
import java.nio.file.Path;

import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import com.alipay.antchain.bridge.plugins.ethereum3.EthereumBBCService;
import com.alipay.antchain.bridge.plugins.ethereum3.abi.AppContract;
import com.alipay.antchain.bridge.plugins.ethereum3.helper.AcbFastRawTransactionManager;
import org.antchainbridge.experiment.ExperimentConfig;
import org.antchainbridge.experiment.OpenLoopWorkload;
import org.antchainbridge.experiment.TransactionSender;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.gas.DefaultGasProvider;

public final class EthereumExperimentSender implements TransactionSender {

    private final EthereumBBCService service;
    private final AppContract app;
    private final String targetDomain;
    private final byte[] targetApp;

    private EthereumExperimentSender(ExperimentConfig config) throws Exception {
        DefaultBBCContext context = new DefaultBBCContext();
        context.setConfForBlockchainClient(Files.readAllBytes(Path.of(config.required("chain.config"))));
        this.service = new EthereumBBCService();
        service.startup(context);
        if (!(service.getAcbEthClient().getRawTransactionManager() instanceof AcbFastRawTransactionManager)) {
            service.shutdown();
            throw new IllegalArgumentException(
                    "Ethereum experiment config must set ethNoncePolicy to FAST for concurrent submissions"
            );
        }
        this.app = AppContract.load(
                config.required("source.app"),
                service.getAcbEthClient().getWeb3j(),
                service.getAcbEthClient().getRawTransactionManager(),
                new DefaultGasProvider()
        );
        this.targetDomain = config.required("target.domain");
        this.targetApp = parseCrossChainId(config.required("target.app"));
    }

    @Override
    public String send(byte[] payload) throws Exception {
        TransactionReceipt receipt = app.sendUnorderedMessage(targetDomain, targetApp, payload).send();
        if (receipt == null || !receipt.isStatusOK()) {
            throw new IllegalStateException(
                    "ethereum source transaction failed: " + (receipt == null ? "no receipt" : receipt.getStatus())
            );
        }
        return receipt.getTransactionHash();
    }

    @Override
    public void close() {
        service.shutdown();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: EthereumExperimentSender EXPERIMENT_PROPERTIES");
        }
        ExperimentConfig config = ExperimentConfig.load(Path.of(args[0]));
        try (EthereumExperimentSender sender = new EthereumExperimentSender(config)) {
            new OpenLoopWorkload().run(config, sender);
        }
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
}
