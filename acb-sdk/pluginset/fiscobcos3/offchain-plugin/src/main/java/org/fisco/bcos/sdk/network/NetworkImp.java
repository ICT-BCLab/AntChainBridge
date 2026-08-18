/*
 * Copyright 2014-2020  [fisco-dev]
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.fisco.bcos.sdk.network;

import io.netty.channel.ChannelHandlerContext;
import org.fisco.bcos.sdk.config.ConfigOption;
import org.fisco.bcos.sdk.config.exceptions.ConfigException;
import org.fisco.bcos.sdk.model.CryptoType;
import org.fisco.bcos.sdk.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import static org.fisco.bcos.sdk.model.CryptoProviderType.HSM;

/**
 * Local compatibility overlay for FISCO BCOS SDK 2.8.0.
 *
 * <p>The upstream implementation tries ECDSA first and silently falls back to SM certificates when
 * ECDSA SSL initialization fails. In the plugin tests and plugin-server runtime this masks the real
 * non-SM error with an SM certificate parsing error. The plugin config already carries
 * sslCryptoType, so start with that exact mode and report the original failure.</p>
 */
public class NetworkImp implements Network {

    private static final Logger logger = LoggerFactory.getLogger(NetworkImp.class);

    private ConnectionManager connManager;
    private final ConfigOption configOption;
    private final MsgHandler handler;

    public NetworkImp(ConfigOption configOption, MsgHandler handler) throws ConfigException {
        this.configOption = configOption;
        this.handler = handler;
        this.connManager = new ConnectionManager(configOption, handler);
    }

    @Override
    public ConfigOption getConfigOption() {
        return configOption;
    }

    @Override
    public int getSslCryptoType() {
        return configOption.getCryptoMaterialConfig().getSslCryptoType();
    }

    @Override
    public void broadcast(Message out) {
        Map<String, ChannelHandlerContext> conns = connManager.getAvailableConnections();
        conns.forEach(
                (peer, ctx) -> {
                    ctx.writeAndFlush(out);
                    logger.trace("send message to {} success", peer);
                });
    }

    @Override
    public void sendToPeer(Message out, String peerIpPort) throws NetworkException {
        ChannelHandlerContext ctx = connManager.getConnectionCtx(peerIpPort);
        if (Objects.nonNull(ctx)) {
            ctx.writeAndFlush(out);
            logger.trace("send message to {} success", peerIpPort);
            return;
        }
        logger.warn("send message to {} failed", peerIpPort);
        throw new NetworkException("Peer not available. Peer: " + peerIpPort);
    }

    @Override
    public List<ConnectionInfo> getConnectionInfo() {
        return connManager.getConnectionInfoList();
    }

    @Override
    public void start() throws NetworkException {
        int sslCryptoType = configOption.getCryptoMaterialConfig().getSslCryptoType();
        if (configOption.getCryptoMaterialConfig().getCryptoProvider() != null
                && configOption.getCryptoMaterialConfig().getCryptoProvider().equalsIgnoreCase(HSM)
                && sslCryptoType == CryptoType.ECDSA_TYPE) {
            throw new NetworkException(
                    "NON-SM not support hardware secure module yet, please do not config cryptoMatirial.cryptoProvider = hsm.");
        }

        logger.info(
                "start connManager with configured {} sslContext",
                sslCryptoType == CryptoType.ECDSA_TYPE ? "ECDSA" : "SM");
        connManager.startConnect(configOption);
        connManager.startReconnectSchedule();
    }

    @Override
    public Map<String, ChannelHandlerContext> getAvailableConnections() {
        return connManager.getAvailableConnections();
    }

    @Override
    public void removeConnection(String peerIpPort) {
        connManager.removeConnection(peerIpPort);
    }

    @Override
    public void setMsgHandleThreadPool(ExecutorService threadPool) {
        connManager.setMsgHandleThreadPool(threadPool);
    }

    @Override
    public ConnectionManager getConnManager() {
        return connManager;
    }

    @Override
    public void stop() {
        logger.debug("stop Network...");
        connManager.stopReconnectSchedule();
        connManager.stopNetty();
    }
}
