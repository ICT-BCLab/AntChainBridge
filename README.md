<div align="center">
  <img alt="am logo" src="https://antchainbridge.oss-cn-shanghai.aliyuncs.com/antchainbridge/document/picture/antchain.png" width="250" >
  <h1 align="center">AntChain Bridge</h1>
</div>

## 介绍

### 以下为原仓库的README内容，本分支新增内容汇总详见CHANGELOG.md。

AntChain Bridge是蚂蚁链开源的区块链之间的可信通信网络，我们致力于打造通用的异构链跨链协议，为Web3世界打造可信通信的基础设施。

仓库AntChainBridge包含了目前所有跨链相关的组件和依赖，并综合给出整体版本，提供一键编译、部署等功能，方便使用者快速启动自己的跨链应用，无论是高可用的生产场景，还是搭建本地Demo环境。

当前AntChain Bridge包含下面的组件，实现了异构链之间的可信通信能力。

- **AntChainBridgePluginSDK**：提供开发者开发AntChain Bridge应用的所有工具，以及AntChain Bridge和社区贡献者🧑‍🤝‍🧑开发的异构链插件，详情参考[README](acb-sdk/README.md)；
- **AntChainBridgeRelayer**：网络中负责监听、传递跨链消息的中继服务，向区块链提供加入AntChain Bridge的入口，同时集成了Embedded BCDNS模块，可以作为中心化的BCDNS提供服务，详情参考[README](acb-relayer/README.md)；
- **AntChainBridgePluginServer**：插件服务负责运行区块链桥接组件（*Blockchain Bridge Component, BBC*）的服务，BBC以插件的形式加载到插件服务，中继等服务通过和插件服务通信实现与区块链的交互，详情参考[README](acb-pluginserver/README.md)；
- **AntChainBridgeCommitteePtc**：Committee PTC基于委员会的形式提供去中心化的跨链验证、背书能力，通过加载AntChain Birdge的异构链数据验证服务（*Hetero-Chain Data Verification Service, HCDVS*）插件实现了AntChain Bridge对于跨链验证的抽象功能，包括共识状态、跨链消息链上存在性等验证，以及给出有效第三方证明等功能，详情参考[README](acb-committeeptc/README.md)；
- **BIF BCDNS Credential Server**：AntChain Bridge团队和中国信通院[星火链网](https://bitfactory.cn/)团队联合研发的区块链域名服务（*BlockChain Domain Name Service, BCDNS*），向AntChain Bridge跨链网络提供区块链域名、跨链身份管理等能力，它基于星火链提供服务；

想要详细了解AntChain Bridge，可以阅读我们[Wiki](https://github.com/AntChainOpenLabs/AntChainBridgePluginSDK/wiki)的内容。

## 版本

### 组件

这里给出AntChain Bridge总版本与其他组件的版本对应关系。

> [!NOTE]  
> AntChain Bridge从1.0.0版本开始将所有组件放在当前仓库，AntChain Bridge未来特性的发布将以整体版本为主，异构链插件版本将独立发布。
>
> 1.0.0版本之前各组件的发布，需要到之前单独的仓库获取。

| AntChainBridge |                   AntChainBridgePluginSDK                    |                    AntChainBridgeRelayer                     |                  AntChainBridgePluginServer                  | AntChainBridgeCommitteePtc | BIF BCDNS Credential Server |
| :------------: | :----------------------------------------------------------: | :----------------------------------------------------------: | :----------------------------------------------------------: | -------------------------- | :-------------------------: |
|  🏅**v1.0.0**   |                          **v1.0.0**                          |                          **v1.0.0**                          |                          **v1.0.0**                          | **v0.1.0**                 |       1.0.0-SNAPSHOT        |
|    *v0.2.0*    |                           *v0.3.0*                           |                           *v0.3.0*                           |                           *v0.3.0*                           | *🈚️*                        |      *v1.0.0-SNAPSHOT*      |
|    *v0.1.1*    | *[v0.2.1](https://github.com/AntChainOpenLabs/AntChainBridgePluginSDK/releases/tag/v0.2.1)* | *[v0.1.0](https://github.com/AntChainOpenLabs/AntChainBridgeRelayer/releases/tag/v0.1.0)* | *[v0.2.2](https://github.com/AntChainOpenLabs/AntChainBridgePluginServer/releases/tag/v0.2.2)* | *🈚️*                        |      *v1.0.0-SNAPSHOT*      |
|    *v0.1.0*    | *[v0.2.0](https://github.com/AntChainOpenLabs/AntChainBridgePluginSDK/releases/tag/v0.2.0)* | *[v0.1.0](https://github.com/AntChainOpenLabs/AntChainBridgeRelayer/releases/tag/v0.1.0)* | *[v0.2.1](https://github.com/AntChainOpenLabs/AntChainBridgePluginServer/releases/tag/v0.2.1)* | *🈚️*                        |      *v1.0.0-SNAPSHOT*      |
|      *🈚️*       | *[v0.1.2](https://github.com/AntChainOpenLabs/AntChainBridgePluginSDK/releases/tag/v0.1.2)* |                             *🈚️*                              | *[v0.2.0](https://github.com/AntChainOpenLabs/AntChainBridgePluginServer/releases/tag/v0.2.0)* | *🈚️*                        |             *🈚️*             |
|      *🈚️*       | *[v0.1.1](https://github.com/AntChainOpenLabs/AntChainBridgePluginSDK/releases/tag/v0.1.1)* |                             *🈚️*                              | *[v0.1.2-SNAPSHOT](https://github.com/AntChainOpenLabs/AntChainBridgePluginServer/releases/tag/v0.1.2-SNAPSHOT)* | *🈚️*                        |              🈚️              |

