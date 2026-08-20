package com.alipay.antchain.bridge.plugins.mychain020;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.math.BigInteger;
import java.util.UUID;

import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.plugins.mychain020.model.ContractAddressInfo;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Client;
import com.alipay.antchain.bridge.plugins.mychain020.sdk.Mychain020Config;
import com.alipay.mychain.sdk.api.utils.Utils;
import com.alipay.mychain.sdk.common.VMTypeEnum;
import com.alipay.mychain.sdk.domain.account.Identity;
import com.alipay.mychain.sdk.domain.transaction.TransactionReceipt;
import com.alipay.mychain.sdk.errorcode.ErrorCode;
import com.alipay.mychain.sdk.vm.EVMOutput;
import com.alipay.mychain.sdk.vm.EVMParameter;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.helpers.NOPLogger;

public final class MychainMonitorAppFlowTool {

    private static final String CONTRACT_BINARY = "/contracts/monitor_demo/MonitorSenderContract.bin";

    private MychainMonitorAppFlowTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            printUsage();
            System.exit(2);
        }

        String configPath = args[0];
        String monitorContract = args[1];
        String receiverDomain = args[2];
        Identity receiverIdentity = new Identity(Hex.decode(normalizeIdentity(args[3])));
        boolean diagnoseOnly = args.length == 5 && "--diagnose-only".equals(args[4]);
        String message = args.length == 5 && !diagnoseOnly
                ? args[4]
                : "mychain monitored demo";

        Mychain020Config config = Mychain020Config.fromJsonString(
                new String(Files.readAllBytes(Paths.get(configPath)), StandardCharsets.UTF_8));
        Mychain020Client client = new Mychain020Client(
                config.toJsonString().getBytes(StandardCharsets.UTF_8),
                NOPLogger.NOP_LOGGER);

        try {
            require(client.startup(), "startup mychain sdk failed");
            printSystemContractDiagnostics(client, config, monitorContract, receiverIdentity);
            if (diagnoseOnly) {
                return;
            }

            String senderContract = "MonitorSender_" + UUID.randomUUID();
            System.out.println("MONITOR_APP_SENDER_CONTRACT=" + senderContract);
            require(
                    client.deployContract(CONTRACT_BINARY, senderContract, VMTypeEnum.EVM, new EVMParameter()),
                    "deploy monitor sender failed");

            EVMParameter configureParameters = new EVMParameter("setMonitorContract(identity)");
            configureParameters.addIdentity(Utils.getIdentityByName(
                    monitorContract,
                    client.getConfig().getMychainHashType()));
            SendResponseResult configureResult = client.callContract(senderContract, configureParameters, true);
            printResponse("MONITOR_APP_CONFIGURE", configureResult);
            requireSuccess(configureResult, "configure monitor failed");

            EVMParameter sendParameters = new EVMParameter("sendUnordered(identity,string,bytes)");
            sendParameters.addIdentity(receiverIdentity);
            sendParameters.addString(receiverDomain);
            sendParameters.addBytes(message.getBytes(StandardCharsets.UTF_8));
            SendResponseResult sendResult = client.callContract(senderContract, sendParameters, true);
            printResponse("MONITOR_APP_SEND", sendResult);
            requireSuccess(sendResult, "send monitored message failed");

            System.out.println("MONITOR_APP_SENDER_IDENTITY=" + Utils.getIdentityByName(
                    senderContract,
                    client.getConfig().getMychainHashType()).hexStrValue());
            System.out.println("MONITOR_APP_TX_HASH=" + sendResult.getTxId());
            System.out.println("MONITOR_APP_RECEIVER_DOMAIN=" + receiverDomain);
            System.out.println("MONITOR_APP_RECEIVER_IDENTITY=" + receiverIdentity.hexStrValue());
            System.out.println("MONITOR_APP_MESSAGE=" + message);
        } finally {
            client.shutdown();
        }
    }

    private static void printSystemContractDiagnostics(
            Mychain020Client client,
            Mychain020Config config,
            String monitorContract,
            Identity receiverIdentity) {
        String sdpContract = ContractAddressInfo.decode(config.getSdpContractName()).getEvmContractAddress();
        String expectedSdpIdentity = Utils.getIdentityByName(
                sdpContract,
                config.getMychainHashType()).hexStrValue();
        String expectedMonitorIdentity = Utils.getIdentityByName(
                monitorContract,
                config.getMychainHashType()).hexStrValue();
        String ptcContract = ContractAddressInfo.resolveEvmContractAddress(config.getPtcContractName());
        String monitorVerifierContract = config.getMonitorVerifierContractName();

        BigInteger monitorControl = localCallUint(client, monitorContract, "getMonitorControl()");
        String monitorProtocol = localCallIdentity(client, monitorContract, "getProtocol()");
        String sdpMonitor = localCallIdentity(client, sdpContract, "monitorAddress()");

        EVMParameter preMonitoringParameters = new EVMParameter("preMonitoring(identity)");
        preMonitoringParameters.addIdentity(receiverIdentity);
        boolean preMonitoring = localCallBoolean(client, monitorContract, preMonitoringParameters);

        System.out.println("MYCHAIN_MONITOR_CONTROL=" + monitorControl);
        System.out.println("MYCHAIN_MONITOR_PROTOCOL=" + monitorProtocol);
        System.out.println("MYCHAIN_EXPECTED_SDP_IDENTITY=" + expectedSdpIdentity);
        System.out.println("MYCHAIN_SDP_MONITOR=" + sdpMonitor);
        System.out.println("MYCHAIN_EXPECTED_MONITOR_IDENTITY=" + expectedMonitorIdentity);
        System.out.println("MYCHAIN_RECEIVER_ALLOWED=" + preMonitoring);
        if (ptcContract != null && !ptcContract.isEmpty()
                && monitorVerifierContract != null && !monitorVerifierContract.isEmpty()) {
            System.out.println("MYCHAIN_MONITOR_VERIFIER_PTC_HUB=" + localCallIdentity(
                    client,
                    monitorVerifierContract,
                    "getPtcHubAddress()"));
            System.out.println("MYCHAIN_EXPECTED_PTC_IDENTITY=" + Utils.getIdentityByName(
                    ptcContract,
                    config.getMychainHashType()).hexStrValue());
        }
    }

    private static BigInteger localCallUint(Mychain020Client client, String contract, String method) {
        TransactionReceipt receipt = localCall(client, contract, new EVMParameter(method));
        return new EVMOutput(Hex.toHexString(receipt.getOutput())).getUint();
    }

    private static String localCallIdentity(Mychain020Client client, String contract, String method) {
        TransactionReceipt receipt = localCall(client, contract, new EVMParameter(method));
        return new EVMOutput(Hex.toHexString(receipt.getOutput())).getIdentity().hexStrValue();
    }

    private static boolean localCallBoolean(
            Mychain020Client client,
            String contract,
            EVMParameter parameters) {
        TransactionReceipt receipt = localCall(client, contract, parameters);
        return new EVMOutput(Hex.toHexString(receipt.getOutput())).getBoolean();
    }

    private static TransactionReceipt localCall(
            Mychain020Client client,
            String contract,
            EVMParameter parameters) {
        TransactionReceipt receipt = client.localCallContract(contract, parameters);
        require(receipt != null, "local call returned no receipt: " + contract);
        require(
                ErrorCode.SUCCESS.getErrorCode() == receipt.getResult(),
                "local call failed: " + contract + ", result=" + receipt.getResult());
        require(receipt.getOutput() != null, "local call returned no output: " + contract);
        return receipt;
    }

    private static void printResponse(String prefix, SendResponseResult result) {
        if (result == null) {
            System.out.println(prefix + "_RESULT=null");
            return;
        }
        System.out.println(prefix + "_TX_HASH=" + result.getTxId());
        System.out.println(prefix + "_CONFIRMED=" + result.isConfirmed());
        System.out.println(prefix + "_SUCCESS=" + result.isSuccess());
        System.out.println(prefix + "_ERROR_CODE=" + result.getErrorCode());
        System.out.println(prefix + "_ERROR_MESSAGE=" + result.getErrorMessage());
    }

    private static void requireSuccess(SendResponseResult result, String action) {
        require(result != null, action + ": empty response");
        require(
                result.isSuccess(),
                action
                        + ": tx=" + result.getTxId()
                        + ", confirmed=" + result.isConfirmed()
                        + ", errorCode=" + result.getErrorCode()
                        + ", errorMessage=" + result.getErrorMessage());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String normalizeIdentity(String identity) {
        String normalized = identity.startsWith("0x") ? identity.substring(2) : identity;
        if (normalized.length() != 64) {
            throw new IllegalArgumentException("receiver identity must contain exactly 32 bytes");
        }
        return normalized;
    }

    private static void printUsage() {
        System.err.println("usage: MychainMonitorAppFlowTool <config> <monitor> <domain> <receiver-identity> [message|--diagnose-only]");
    }
}
