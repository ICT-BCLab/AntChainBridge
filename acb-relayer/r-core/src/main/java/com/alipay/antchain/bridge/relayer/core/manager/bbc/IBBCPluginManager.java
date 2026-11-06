package com.alipay.antchain.bridge.relayer.core.manager.bbc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.alipay.antchain.bridge.relayer.commons.constant.PluginServerStateEnum;
import com.alipay.antchain.bridge.relayer.commons.model.PluginServerInfo;
import com.alipay.antchain.bridge.relayer.core.types.pluginserver.IBBCServiceClient;
import com.alipay.antchain.bridge.relayer.core.types.pluginserver.IPluginServerClient;

public interface IBBCPluginManager {

    /** Executors owned by this manager, drained before shared resources close. */
    Map<String, ExecutorService> getManagedExecutors();

    IBBCServiceClient createBBCClient(String psId, String product, String domain);

    void registerPluginServer(String psId, String address, String properties);

    void deletePluginServer(String psId);

    void startPluginServer(String psId);

    void forceStartPluginServer(String psId);

    void stopPluginServer(String psId);

    IPluginServerClient getPluginServerClient(String psId);

    List<String> getAllPluginServerId();

    PluginServerStateEnum getPluginServerState(String psId);

    List<String> getProductsSupportedByPsId(String psId);

    List<String> getDomainsSupportedByPsId(String psId);

    PluginServerInfo getPluginServerInfo(String psId);

    void updatePluginServerInfo(String psId);
}
