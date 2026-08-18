package com.alipay.antchain.bridge.plugins.fiscobcos3.demo.contract;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.fisco.bcos.sdk.abi.FunctionReturnDecoder;
import org.fisco.bcos.sdk.abi.TypeReference;
import org.fisco.bcos.sdk.abi.datatypes.Address;
import org.fisco.bcos.sdk.abi.datatypes.DynamicBytes;
import org.fisco.bcos.sdk.abi.datatypes.Function;
import org.fisco.bcos.sdk.abi.datatypes.Type;
import org.fisco.bcos.sdk.abi.datatypes.Utf8String;
import org.fisco.bcos.sdk.abi.datatypes.generated.Bytes32;
import org.fisco.bcos.sdk.abi.datatypes.generated.tuples.generated.Tuple1;
import org.fisco.bcos.sdk.abi.datatypes.generated.tuples.generated.Tuple3;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.contract.Contract;
import org.fisco.bcos.sdk.crypto.CryptoSuite;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.model.CryptoType;
import org.fisco.bcos.sdk.model.TransactionReceipt;
import org.fisco.bcos.sdk.model.callback.TransactionCallback;
import org.fisco.bcos.sdk.transaction.model.exception.ContractException;

@SuppressWarnings("unchecked")
public class MonitorSenderContract extends Contract {
    public static final String[] BINARY_ARRAY = {"608060405234801561001057600080fd5b50610355806100206000396000f30060806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680636725313114610051578063adc3de1814610094575b600080fd5b34801561005d57600080fd5b50610092600480360381019080803573ffffffffffffffffffffffffffffffffffffffff169060200190929190505050610151565b005b3480156100a057600080fd5b5061014f6004803603810190808035600019169060200190929190803590602001908201803590602001908080601f0160208091040260200160405190810160405280939291908181526020018383808284378201915050505050509192919290803590602001908201803590602001908080601f0160208091040260200160405190810160405280939291908181526020018383808284378201915050505050509192919290505050610194565b005b806000806101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff16021790555050565b60008060009054906101000a900473ffffffffffffffffffffffffffffffffffffffff1690508073ffffffffffffffffffffffffffffffffffffffff1663c610a7768486856040518463ffffffff167c01000000000000000000000000000000000000000000000000000000000281526004018080602001846000191660001916815260200180602001838103835286818151815260200191508051906020019080838360005b8381101561025657808201518184015260208101905061023b565b50505050905090810190601f1680156102835780820380516001836020036101000a031916815260200191505b50838103825284818151815260200191508051906020019080838360005b838110156102bc5780820151818401526020810190506102a1565b50505050905090810190601f1680156102e95780820380516001836020036101000a031916815260200191505b5095505050505050600060405180830381600087803b15801561030b57600080fd5b505af115801561031f573d6000803e3d6000fd5b50505050505050505600a165627a7a72305820d588a9faa0c272738b04e451b6fcbe2274008ae887a797b6a549d0775617ba3b0029"};

    public static final String BINARY = org.fisco.bcos.sdk.utils.StringUtils.joinAll("", BINARY_ARRAY);

    public static final String[] SM_BINARY_ARRAY = {"608060405234801561001057600080fd5b50610355806100206000396000f30060806040526004361061004c576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff1680636725313114610051578063adc3de1814610094575b600080fd5b34801561005d57600080fd5b50610092600480360381019080803573ffffffffffffffffffffffffffffffffffffffff169060200190929190505050610151565b005b3480156100a057600080fd5b5061014f6004803603810190808035600019169060200190929190803590602001908201803590602001908080601f0160208091040260200160405190810160405280939291908181526020018383808284378201915050505050509192919290803590602001908201803590602001908080601f0160208091040260200160405190810160405280939291908181526020018383808284378201915050505050509192919290505050610194565b005b806000806101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff16021790555050565b60008060009054906101000a900473ffffffffffffffffffffffffffffffffffffffff1690508073ffffffffffffffffffffffffffffffffffffffff1663c610a7768486856040518463ffffffff167c01000000000000000000000000000000000000000000000000000000000281526004018080602001846000191660001916815260200180602001838103835286818151815260200191508051906020019080838360005b8381101561025657808201518184015260208101905061023b565b50505050905090810190601f1680156102835780820380516001836020036101000a031916815260200191505b50838103825284818151815260200191508051906020019080838360005b838110156102bc5780820151818401526020810190506102a1565b50505050905090810190601f1680156102e95780820380516001836020036101000a031916815260200191505b5095505050505050600060405180830381600087803b15801561030b57600080fd5b505af115801561031f573d6000803e3d6000fd5b50505050505050505600a165627a7a72305820d588a9faa0c272738b04e451b6fcbe2274008ae887a797b6a549d0775617ba3b0029"};

    public static final String SM_BINARY = org.fisco.bcos.sdk.utils.StringUtils.joinAll("", SM_BINARY_ARRAY);

    public static final String[] ABI_ARRAY = {"[{\"constant\":false,\"inputs\":[{\"name\":\"_monitor_address\",\"type\":\"address\"}],\"name\":\"setMonitorAddress\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"},{\"constant\":false,\"inputs\":[{\"name\":\"receiver\",\"type\":\"bytes32\"},{\"name\":\"domain\",\"type\":\"string\"},{\"name\":\"_msg\",\"type\":\"bytes\"}],\"name\":\"sendMonitored\",\"outputs\":[],\"payable\":false,\"stateMutability\":\"nonpayable\",\"type\":\"function\"}]"};

    public static final String ABI = org.fisco.bcos.sdk.utils.StringUtils.joinAll("", ABI_ARRAY);

    public static final String FUNC_SETMONITORADDRESS = "setMonitorAddress";

    public static final String FUNC_SENDMONITORED = "sendMonitored";

    protected MonitorSenderContract(String contractAddress, Client client, CryptoKeyPair credential) {
        super(getBinary(client.getCryptoSuite()), contractAddress, client, credential);
    }

    public static String getBinary(CryptoSuite cryptoSuite) {
        return (cryptoSuite.getCryptoTypeConfig() == CryptoType.ECDSA_TYPE ? BINARY : SM_BINARY);
    }

    public TransactionReceipt setMonitorAddress(String _monitor_address) {
        final Function function = new Function(
                FUNC_SETMONITORADDRESS,
                Arrays.<Type>asList(new org.fisco.bcos.sdk.abi.datatypes.Address(_monitor_address)),
                Collections.<TypeReference<?>>emptyList());
        return executeTransaction(function);
    }

    public byte[] setMonitorAddress(String _monitor_address, TransactionCallback callback) {
        final Function function = new Function(
                FUNC_SETMONITORADDRESS,
                Arrays.<Type>asList(new org.fisco.bcos.sdk.abi.datatypes.Address(_monitor_address)),
                Collections.<TypeReference<?>>emptyList());
        return asyncExecuteTransaction(function, callback);
    }

    public String getSignedTransactionForSetMonitorAddress(String _monitor_address) {
        final Function function = new Function(
                FUNC_SETMONITORADDRESS,
                Arrays.<Type>asList(new org.fisco.bcos.sdk.abi.datatypes.Address(_monitor_address)),
                Collections.<TypeReference<?>>emptyList());
        return createSignedTransaction(function);
    }

    public Tuple1<String> getSetMonitorAddressInput(TransactionReceipt transactionReceipt) {
        String data = transactionReceipt.getInput().substring(10);
        final Function function = new Function(FUNC_SETMONITORADDRESS,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}));
        List<Type> results = FunctionReturnDecoder.decode(data, function.getOutputParameters());
        return new Tuple1<String>(

                (String) results.get(0).getValue()
                );
    }

    public TransactionReceipt sendMonitored(byte[] receiver, String domain, byte[] _msg) {
        final Function function = new Function(
                FUNC_SENDMONITORED,
                Arrays.<Type>asList(new org.fisco.bcos.sdk.abi.datatypes.generated.Bytes32(receiver),
                new org.fisco.bcos.sdk.abi.datatypes.Utf8String(domain),
                new org.fisco.bcos.sdk.abi.datatypes.DynamicBytes(_msg)),
                Collections.<TypeReference<?>>emptyList());
        return executeTransaction(function);
    }

    public byte[] sendMonitored(byte[] receiver, String domain, byte[] _msg, TransactionCallback callback) {
        final Function function = new Function(
                FUNC_SENDMONITORED,
                Arrays.<Type>asList(new org.fisco.bcos.sdk.abi.datatypes.generated.Bytes32(receiver),
                new org.fisco.bcos.sdk.abi.datatypes.Utf8String(domain),
                new org.fisco.bcos.sdk.abi.datatypes.DynamicBytes(_msg)),
                Collections.<TypeReference<?>>emptyList());
        return asyncExecuteTransaction(function, callback);
    }

    public String getSignedTransactionForSendMonitored(byte[] receiver, String domain, byte[] _msg) {
        final Function function = new Function(
                FUNC_SENDMONITORED,
                Arrays.<Type>asList(new org.fisco.bcos.sdk.abi.datatypes.generated.Bytes32(receiver),
                new org.fisco.bcos.sdk.abi.datatypes.Utf8String(domain),
                new org.fisco.bcos.sdk.abi.datatypes.DynamicBytes(_msg)),
                Collections.<TypeReference<?>>emptyList());
        return createSignedTransaction(function);
    }

    public Tuple3<byte[], String, byte[]> getSendMonitoredInput(TransactionReceipt transactionReceipt) {
        String data = transactionReceipt.getInput().substring(10);
        final Function function = new Function(FUNC_SENDMONITORED,
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bytes32>() {}, new TypeReference<Utf8String>() {}, new TypeReference<DynamicBytes>() {}));
        List<Type> results = FunctionReturnDecoder.decode(data, function.getOutputParameters());
        return new Tuple3<byte[], String, byte[]>(

                (byte[]) results.get(0).getValue(),
                (String) results.get(1).getValue(),
                (byte[]) results.get(2).getValue()
                );
    }

    public static MonitorSenderContract load(String contractAddress, Client client, CryptoKeyPair credential) {
        return new MonitorSenderContract(contractAddress, client, credential);
    }

    public static MonitorSenderContract deploy(Client client, CryptoKeyPair credential) throws ContractException {
        return deploy(MonitorSenderContract.class, client, credential, getBinary(client.getCryptoSuite()), "");
    }
}
