package org.antchainbridge.experiment.mychain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Client;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Config;
import com.alipay.mychain.sdk.crypto.hash.Hash;
import com.alipay.mychain.sdk.domain.account.Identity;
import com.alipay.mychain.sdk.vm.EVMParameter;
import org.antchainbridge.experiment.ExperimentConfig;
import org.antchainbridge.experiment.OpenLoopWorkload;
import org.antchainbridge.experiment.TransactionSender;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.helpers.NOPLogger;

public final class MychainExperimentSender implements TransactionSender {

    private final Mychain020Client client;
    private final String sourceApp;
    private final Identity targetApp;
    private final String targetDomain;

    private MychainExperimentSender(ExperimentConfig config) throws Exception {
        Mychain020Config chainConfig = Mychain020Config.fromJsonString(
                Files.readString(Path.of(config.required("chain.config")), StandardCharsets.UTF_8)
        );
        this.client = new Mychain020Client(
                chainConfig.toJsonString().getBytes(StandardCharsets.UTF_8),
                NOPLogger.NOP_LOGGER
        );
        if (!client.startup()) {
            throw new IllegalStateException("startup Mychain SDK failed");
        }
        this.sourceApp = config.required("source.app");
        this.targetApp = new Identity(new Hash(Hex.decode(normaliseIdentity(config.required("target.app")))));
        this.targetDomain = config.required("target.domain");
    }

    @Override
    public String send(byte[] payload) {
        EVMParameter parameters = new EVMParameter("sendUnordered(identity,string,bytes)");
        parameters.addIdentity(targetApp);
        parameters.addString(targetDomain);
        parameters.addBytes(payload);
        SendResponseResult response = client.callContract(sourceApp, parameters, true);
        if (response == null || !response.isSuccess()) {
            throw new IllegalStateException(
                    "Mychain source transaction failed: "
                            + (response == null ? "no response" : response.getErrorMessage())
            );
        }
        return response.getTxId();
    }

    @Override
    public void close() {
        client.shutdown();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: MychainExperimentSender EXPERIMENT_PROPERTIES");
        }
        ExperimentConfig config = ExperimentConfig.load(Path.of(args[0]));
        try (MychainExperimentSender sender = new MychainExperimentSender(config)) {
            new OpenLoopWorkload().run(config, sender);
        }
    }

    private static String normaliseIdentity(String value) {
        String raw = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if (!raw.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("target.app must be a 20-byte address or 32-byte identity");
        }
        return raw.length() == 40 ? "000000000000000000000000" + raw : raw;
    }
}
