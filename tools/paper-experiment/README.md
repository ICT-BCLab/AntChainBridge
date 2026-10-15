# 跨链事务时延实验工具

本目录用于论文跨链事务时延实验，提供 Relayer 指标聚合、512B 开环负载和三种源链发送适配器。工具不会部署或升级合约、切换 Monitor 状态、注册区块链，也不会查询 Gas。

## 实验边界

正式实验逐条运行以下六条有向路径：`ETH→MYC`、`ETH→FISCO`、`MYC→ETH`、`MYC→FISCO`、`FISCO→ETH`、`FISCO→MYC`。每条路径分别运行 Base、Xun 两种方案，每种方案 10 轮；每轮固定 5 TPS、持续 30 秒，共 150 笔，合计 18000 笔。协议固定为 unordered SDP V1。

Base 必须使用独立的原始非监管链路，不能用 `MONITOR_CLOSE` 代替：

| 方案 | Ethereum | Mychain | FISCO |
| --- | --- | --- | --- |
| Base | `ethereum2` | `mychain` | `fiscobcos` |
| Xun | `ethereum3` | `mychain2` | `fiscobcos3` |

Base 与 Xun 可连接相同链节点，但必须使用各自预先部署并绑定好的应用/系统合约和 Relayer 插件配置。发送工具只调用已有应用合约，不会自动切换共享环境。`source.product` 必须填写 Relayer 实际注册的产品名，而不是 Java 发送适配器的实现名。

## Relayer 和监管系统配置

所有 Base、Xun Relayer 都使用同一份埋点代码。正式运行时配置：

```yaml
relayer:
  platform_report:
    enabled: false
  experiment_metrics:
    enabled: true
```

指标单独写入 `${logging.file.path}/antchain-bridge-relayer/experiment-metrics.log`，每行以 `EXPERIMENT_METRIC ` 开头。开关默认关闭，关闭时不改变现有运行行为。

Xun 方案将监管验证延迟设为 1000ms；Base 不调用监管系统：

```shell
JAVA_OPTS="-Dmonitor.verify.delay.ms=1000" ./SimpleMonitorSystem/bin/start.sh
```

该延迟只作用于 `verifyCrossChainMessageInMonitorSystem`，不作用于 `relayUcpToMonitorSystem`。

## 构建

实验工具使用 Java 21。首次构建前需要确保当前分支的三个插件已安装到本地 Maven 仓库。Mychain 插件本身需用 Java 8 构建；Ethereum3 的 ABI 源码由 web3j 生成且被 Git 忽略，应从插件目录执行生成命令：

```shell
cd acb-sdk/pluginset/ethereum3/offchain-plugin
mvn org.web3j:web3j-maven-plugin:4.12.1:generate-sources
mvn -DskipTests install

cd ../../fiscobcos/offchain-plugin
mvn -DskipTests install

cd ../../fiscobcos3/offchain-plugin
mvn -DskipTests install

cd ../../mychain0.20/offchain-plugin
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 \
PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH \
mvn -DskipTests install

cd ../../../../tools/paper-experiment
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH \
mvn install
```

## 单轮发送

复制 `experiment.properties.example` 到仓库外的受限目录并填写链 SDK 配置、已有应用合约地址、域名、产品名、区块链 ID、代码提交号和实际Relayer插件制品路径。工具会自动计算该制品的SHA-256写入manifest。不要提交私钥或链配置。

Ethereum 配置必须使用 `ethNoncePolicy=FAST`；适配器会在发送前检查，以防并发 nonce 冲突。`target.app` 可填写 20 字节 EVM 地址或 32 字节 Mychain identity；20 字节地址会左侧补零为跨链 `bytes32`。

发送端和 Relayer 不在同一主机时，先用 NTP/chrony 同步，并把两台主机测得的偏差分别写入 `clock.offset.ms` 和 `relayer.clock.offset.ms`。设置 `experiment.formal=true` 后，任一绝对偏差超过 20ms或未填写都会拒绝启动。

从本目录选择一个适配器运行：

```shell
mvn -f ethereum-adapter/pom.xml exec:java \
  -Dexec.mainClass=org.antchainbridge.experiment.ethereum.EthereumExperimentSender \
  -Dexec.args=/secure/path/experiment.properties

mvn -f mychain-adapter/pom.xml exec:java \
  -Dexec.mainClass=org.antchainbridge.experiment.mychain.MychainExperimentSender \
  -Dexec.args=/secure/path/experiment.properties

mvn -f fisco-adapter/pom.xml exec:java \
  -Dexec.mainClass=org.antchainbridge.experiment.fisco.FiscoExperimentSender \
  -Dexec.args=/secure/path/experiment.properties
```

FISCO 发送器根据 `source.product` 选择实现：`fiscobcos` 只允许用于 Base，
`fiscobcos3` 只允许用于 Xun；其他 FISCO 产品名会在连接链节点前被拒绝。

每个进程只初始化一次 SDK/账号，使用绝对单调时钟在 `0/200/400/600/800ms` 调度。发送失败会保留为失败样本，不补发。若任一笔 `scheduleLatenessMs > 200`，该轮会被标为无效。

每条路径一次只运行一轮；确认本轮完成并保存全部原始文件后再运行下一轮或下一条路径。不要并行运行不同路径。

## 等待终态并聚合

30 秒只是注入期。发送完成后运行聚合器，它会继续读取 Relayer 日志，最多等待 900 秒，直至所有样本成功确认或明确失败：

```shell
python3 aggregate_latency.py \
  --sender /data/run/round-01/sender.jsonl \
  --relayer /data/run/experiment-metrics.log \
  --output-dir /data/run/final \
  --expected-samples 150 \
  --wait-seconds 900
```

如需一次汇总10轮，可在 `--sender` 后列出10份发送日志；如Relayer日志发生轮转，也可在 `--relayer` 后依次提供多个文件。输出包括规范化的 `sender.jsonl`、`relayer.jsonl`、逐笔 `transactions.csv`、每轮 `summary.csv` 和跨轮 `pooled-summary.csv`；每个发送目录仍保留自己的 `run-manifest.json`。聚合器使用 `sourceProduct + sourceBlockchainId + sourceTxHashKey` 关联 t0/t1，再用 `ucpId` 关联 t2/t3。乱序和重复事件取最早有效值，缺失、失败、超时、负时延均保留为显式状态。

只有 150 笔全部成功、无缺失、无无效时延且无调度超限时，`validRun` 才为 `true`。`pooled-summary.csv` 同时给出有效轮的轮均值平均值和逐笔pooled统计；正式论文主表使用前者，pooled数据只用于核查。

## 时间定义

- `t0`：发送线程调用源链 SDK 前。
- `t1`：本地扫描得到的 UCP 成功写入 Relayer 数据库且写入数量检查通过后。
- `t2`：Relayer 调用目的链插件 `recvPkgFromRelayer` 前捕获；日志在调用后写出，但保留预先捕获时间。
- `t3`：目的链交易第一次确认且执行成功。

最终计算 `Ts=t1-t0`、`To=t2-t1`、`Tt=t3-t2`、`Ttotal=t3-t0`。
