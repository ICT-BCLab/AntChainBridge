# 本分支新增功能总结

## 2026.08.18 FISCO-BCOS 2监管插件拆分
- 保留原有`fiscobcos2`插件不变，新增产品名为`fiscobcos3`、插件ID为`plugin-fiscobcos3`的监管版FISCO-BCOS 2插件，使普通版与监管版可在同一PluginServer中独立加载。
- 为`fiscobcos3`增加Monitor和MonitorVerifier监管合约、监管合约部署与上下文恢复、监管指令转发、跨链消息监管校验及HCDVS共识状态验证能力。
- 隔离`fiscobcos2`与`fiscobcos3`使用的WeDPR原生库加载路径，解决两个FISCO插件在PF4J不同类加载器中同时启动时的原生库冲突。
- 补充FISCO监管流程、共识状态校验和双插件启动测试；本地验证AM、SDP、Monitor及MonitorVerifier合约部署成功，Relayer能够正常监听区块。

## 2026.07.24 Mychain0.10插件适配跨链监管功能
- 为Mychain 0.10 EVM插件新增Monitor和MonitorVerifier监管合约，支持监管开关、发送方/接收方黑名单、监管指令验签与执行，并在SDPv1转发时保留原始发送方身份。
- 打通PTC Hub、MonitorVerifier、Monitor和SDP之间的合约配置及TPBTA监管节点背书同步，实现BBC监管合约部署、监管配置和监管指令转发接口。
- 增加监管合约上下文恢复和旧版Monitor合约自动升级逻辑，修复插件重启后PTC合约状态恢复、AM重复设置协议以及空WASM合约地址进入共识状态的问题。
- 补充Mychain监管合约编译脚本、部署字节码和单元测试。

## 2026.07.23 Dioxide插件、SimpleMonitorSystem优化 适配课题1POST接口
- 优化dioxide/dioxide2提交跨链交易时为异步提交，并且等交易finalized后再返回confirmed为true
- SimpleMonitorSystem能作为系统服务启动，在后台运行（与其他组件类似，通过./bin/start.sh ./bin/stop.sh启动和关闭）
- 适配课题1提出的4个POST接口，relayer拿到ucp、获得ucp的监管结果、提交ucp到目的链、确认ucp在目的链落块归档四个时间向课题1提供ucp的相关执行结果。

## 2026.05.29 Dioxide插件txhash存储优化
- 调整dioxide/dioxide2插件读取跨链消息时blockhash和txhash的处理方式，不再对Dioxide原始hash做sha256或hex解码，而是按UTF-8字节传给relayer，使数据库中存储的hex值可以还原出Dioxide链上的原始hash。
- 将relayer中ucp_pool表的blockhash和txhash字段长度从varchar(66)调整为varchar(128)，兼容Dioxide原始52字符hash经过relayer统一hex编码后形成的104字符存储值。
- 同步更新测试环境ddl.sql中的ucp_pool表字段长度，避免测试库或新初始化环境仍使用旧的varchar(66)限制。
- 将dioxide/dioxide2插件relayMsgToAuthMsg的默认gaslimit调整为50000000，并保留Dioxide链返回的原始txhash作为发送回执txhash。

## 2026.05.28 插件优化
- 将dioxide/dioxide2插件relayMsgToAuthMsg的默认gaslimit调整为10000000，避免项目调试出现gas不够的情况

## 2026.04.28 插件更新与系统优化
- 将dioxide插件和以太坊插件拆成dioxide、dioxide2、ethereum2、ethereum3，其中dioxide和ethereum2插件不保留监管层，dioxide2和ethereum3保留监管层，同时系统只对将使用dioxide2发出的跨链消息转发至课题四监管系统，而不对dioxide作处理。
- 优化监管节点兼容性，让其支持处理不含监管层的插件转发的消息。
- 兼容不含监管层的mychain插件在本系统的使用。

## 2026.04.21 系统优化
- 在SimpleMonitorSystem中更新了项目UcpParser和包含ucp实例的logs/monitor-system.log，按照最新的解析方式进行ucp的解析。
- 为避免信息在公网上明文传输，更新了监管节点和课题4监管系统之间的gRPC通信为单向TLS。因为监管节点和监管系统都是既作为服务端又作为客户端，所以双方都需要提供TLS证书。

## 2026.4.8 插件适配
- 插件适配：适配了国际开放区块链Dioxide插件
  - 为该插件添加了监管合约框架，跨链消息传递过程扩展成了Dapp-Monitor-SDP-AM，适配了监管合约层的编解码，使其能在适配了跨链监管的本系统下正常运行。
  - 为Dioxide插件适配了特殊逻辑，使其在不支持PTC的链下监管功能执行的情况下，系统能够将跨链消息转发至课题四监管系统。

## 2026.1.15 新增UCP实例
在SimpleMonitorSystem中新增了包含ucp实例的logs/monitor-system.log和项目UcpParser，说明了提供的ucp的测试环境并打印了部分字段以供参考。项目UcpParser中有详细注释说明。


## 基本说明

本仓库基于[AntChainOpenLabs
AntChainBridge](https://github.com/AntChainOpenLabs/AntChainBridge)仓库开发，加入了跨链监管功能，完成了如下内容：
- **监管节点的开发**：完成了与课题四监管系统对接所需的v1版接口，以便提供跨链消息内容和接收监管指令。

- **部分区块链对监管的支持**：修改了ethereum插件，加入了监管协议，支持事前、事中以及事后（接收监管指令并存储）监管；支持对AntChainBridge中有序和无序消息的监管；**需要注意的是，目前仅支持SDPv1版本的消息。**

具体部署和对接使用的监管设计文档，请详见项目的语雀知识库。


## 含监管的流程图示
- 下图为含监管的跨链流程图：

![](docs/images/含监管的跨链流程图.png)


## 其他说明

### 跨链消息结构设计变更说明
监管合约作为SDP上层合约，封装DApp消息的同时增加监管字段monitor_type和监管信息monitor_msg。SDP和AM合约的消息字段详见 [Wiki](https://github.com/AntChainOpenLabs/AntChainBridge/wiki/%E5%8C%BA%E5%9D%97%E9%93%BE%E6%A1%A5%E6%8E%A5%E7%BB%84%E4%BB%B6%E5%BC%80%E5%8F%91%E6%89%8B%E5%86%8C-V1#31-%E5%90%88%E7%BA%A6%E5%8E%9F%E7%90%86%E4%BB%8B%E7%BB%8D)。

<img src="docs/images/含监管的跨链消息结构.png"  style="zoom: 33%;" />

| monitor_type值(uint32类型) | monitor_type含义                                             | monitor_msg含义(string类型) |
| ---------------------- | ------------------------------------------------------------ | --------------------------- |
| 1                      | 发送方发出的不要求监管的跨链消息                             | 可选                        |
| 2                      | 对于发送方：发出要求监管的跨链消息。对于接收方：成功接收到带监管的跨链消息 | 可选                        |
| 3                      | 监管未通过，回滚到发送方的监管回滚消息                       | 可选，如监管未通过的原因    |


### 背书策略配置说明
为加入antchain的区块链配置背书策略时，监管节点需要设置为true，举例说明如下。
- 当监管开启时，监管节点会向监管系统请求跨链消息的合法性，**合法则返回一个正确签名，不合法则返回一个空签名**。
- 当监管关闭时，监管节点的运行逻辑和其他节点完全相同，只是不会在链上合约进行签名验证。
```
{
    "committee_id": "default",
    "endorsers": [
        {
            "node_id": "node1",
            "node_public_key": {
                "key_id": "default",
                "public_key": ""
            },
            "required": true
        },
        {
            "node_id": "monitor-node",
            "node_public_key": {
                "key_id": "default",
                "public_key": ""
            },
            "required": true
        }
    ],
    "policy": {
        "threshold": ">=0"
    }
}
```


### 监管指令结构说明
acb-committeeptc/monitor-node/src/main/proto/monitorSystemgrpc.proto中的监管指令结构如下：
```
message MonitorOrder {
  string product = 1;
  string domain = 2;
  uint64 monitorOrderType = 3;
  string senderDomain = 4;
  string fromAddress = 5;
  string receiverDomain = 6;
  string toAddress = 7;
  string transactionContent = 8;
  string extra = 9;
}
```
监管节点会解析出监管指令各字段，并构造包含监管指令的交易发送到指定区块链的监管合约，最终由监管合约更新监管规则。该结构各字段含义如下：
- product
  - 监管指令要下发到的区块链的类型，例如etherum2，fiscobcos等
- domain
  - 监管指令要下发到的区块链的域名
- monitorOrderType
  - 监管指令的类型。该字段长度为32bit，采用了分层编码的设计方式（如下图），分为主类型和子类型，每种主类型标识一种监管维度，每种主类型下分多种子类型
  - 在当前设计中，每个主类型占1bit，每个子类型占3bit，即每种主类型共有8种子类型
  - 主类型的具体含义由监管系统定义。以“黑名单”作为主类型来举例，该主类型的子类型可以包含：
    - 禁止本区块链的应用合约a发送跨链交易；
    - 禁止本区块链向区块链B的应用合约b发送跨链交易；
    - 禁止本区块链向区块链B发送跨链交易等。

<img src="docs/images/监管指令类型monitorOrderType的结构.png"  style="zoom: 33%;" />

- senderDomain
  - 跨链过程中源区块链域名，处理方式随monitorOrderType含义而变化
  - 例如监管指令是“禁止某区块链域名发送跨链消息”，则监管合约会把senderDomain加入黑名单，最终效果为该区块链无法在跨链系统发送跨链消息。
- fromAddress
  - 跨链过程中源区块链的应用合约地址，处理方式随monitorOrderType含义而变化
  - 例如监管指令是“禁止某区块链的某应用合约发送跨链消息”，则监管合约会把fromAddress加入黑名单，最终效果为区块链的该应用合约无法在该跨链系统发送跨链消息。
- receiverDomain
  - 跨链过程中目的区块链域名，处理方式随monitorOrderType含义而变化
- toAddress
  - 跨链过程中目的区块链的应用合约地址，处理方式随monitorOrderType含义而变化。
- transactionContent
  - 针对可能要对跨链过程中的原始跨链消息内容本身进行审查而设计了该字段，用于在链上审查跨链消息内容的合规性。
- extra
  - 额外信息。用于存放监管系统希望在链上存储的一些监管指令描述，或者上述字段未充分考虑的情况等，也可为空。

目前监管合约中对监管指令的支持，只完成了**合约黑名单**和**控制监管开关**两种功能。
- 合约黑名单功能对应的监管指令，用二进制表示如下：
  - **1000** 0000 0000 0000 0000 0000 0000 0000
  - 即32bit中第一对4bit组表示“黑名单”及其子类型。子类型"000"表示加入黑名单，"001"表示移除出黑名单。
- 控制监管开关的监管指令，用二进制表示如下：
  -  0000 **1000** 0000 0000 0000 0000 0000 0000
  -  即32bit中第二对4bit组表示“监管控制”及其子类型。子类型"000"表示关闭监管，"001"表示开启监管。


## 注意事项
**如果需要对一条链chain-B下达监管指令，该链必须先接收一条跨链消息。** 因为按照系统设计，如果chain-A没有接收过跨链消息，链上合约上就不会存储tpbta这个信息，从而无法获取监管节点公钥，无法完成监管指令的签名验证，导致无法成功接收监管指令。
