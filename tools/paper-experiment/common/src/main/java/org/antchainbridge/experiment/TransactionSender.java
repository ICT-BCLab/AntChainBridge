package org.antchainbridge.experiment;

public interface TransactionSender extends AutoCloseable {

    String send(byte[] payload) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
