# Ethereum 采集/PTC 收据日志索引修复

## 根因和边界

Ethereum JSON-RPC 的 Log.logIndex 在整个区块内连续编号；receipt proof 只证明一笔交易的收据，
其 logs 数组索引从 0 开始。旧 Ethereum2/3 采集器把前者存入 ledger.logIndex，
PTC 却把它当作后者，导致同块后续交易被拒绝。

现场原测试 UCP 2fc32b4024cc8ab23959d321a6d16c1647d21033a97a70307c88162f595126cb
存储索引2；源交易收据仅2条日志，其 RPC 索引为[2,3]。PTC 日志同时记录越界错误。
三批32条普通消息共96条中，只有每批第一条成功，其余93条停在PENDING，
未进入Dioxide账户ISN分配。因此它是独立于ISN冲突的第二个阻塞。

过滤采集还有同类问题：对每一个过滤事件再遍历该收据全部AM事件，N个事件产生N²条消息，
且正文来自内层事件，ledger来自外层事件。修复后每条过滤日志只生成一条对应消息。

## 格式与验证规则

- 新ledger.logIndex、receiptLogIndex均为receipt-local位置；原sendAuthMessageLog.logIndex保留RPC区块级值。
- 两种采集模式都从完整交易收据确定位置，核对交易哈希/索引及日志地址、topics、data、区块哈希、RPC索引。
- PTC先验证原receipt proof并匹配可信共识receipts root，再校验receipt transaction index。
- 显式receiptLogIndex存在时，必须与logIndex相等、范围合法、该位置的完整事件匹配；失败不回退。
- 旧消息没有receiptLogIndex：在已验证的收据内唯一匹配地址、全部topics和data；同时检查原索引符合已匹配的局部位置或RPC全局索引。
- AM合约必须来自可信共识；AM事件类型及解码正文必须与UCP正文一致。
- 无匹配、同收据多个完全相同旧事件、负数/空索引、篡改字段均拒绝。不会取模、随意减偏移、选择第一项，或请求RPC代替证明。
- 不改UCP ID、原raw_message/proof，不自动重发历史失败交易，不改任何链上合约/地址。

## 定向测试

Java21，按现有项目说明先准备Web3j生成的ABI包装类，再执行每个插件：

```sh
mvn -f acb-sdk/pluginset/ethereum2/offchain-plugin/pom.xml \
  -Dtest=EthereumReceiptLogIndexTest,EthereumCollectorLogIndexTest,EthereumReceiptProofCompatibilityTest,EthereumHcdvsTest package
# ethereum3 使用相同测试类名，替换路径即可。
```

每个插件19项：9项字段/边界测试、1项完整RPC采集双模式回归、5项完整证明兼容/篡改回归、4项原共识验证测试。
测试证明：两个不同事件只采集两条；区块索引越界的旧事件仍须通过原证明；错误显式索引、伪造根、错误正文均不能获背书。

本次环境的旧Web3j生成器不认识新版Solidity发布索引linux_arm64_url字段，
构建复用了工作区中既有、Git忽略的生成ABI（Ethereum2原合约相同；Ethereum3与上次生产包一致）。
这不改变合约，不应通过关闭证明校验解决构建问题。

## 部署与回滚

采集：Plugin Server的Ethereum2/3插件。验证：三个committee-node和一个monitor-node的同名插件。
不替换PTC主程序、PS主程序、Relayer主程序；Dioxide插件和ISN协调表保持现状。

部署先备份插件、主程序与配置，暂停Relayer调度，停止目标进程后替换插件，
全部验证节点和PS就绪再恢复原Relayer。主程序短暂停止期间PTC连接Relayer嵌入BCDNS的8090端口失败属预期，
恢复后应检查消失；不能把端口监听单独视为业务验收。

服务器备份目录：/root/workspace2026/ethereum-log-index-20260904/backup，目录0700、含密配置0600。
通过SHA校验的精确字节差量还原完整构建包，不是跳过验证的热补丁。

| 包 | SHA-256 |
| --- | --- |
| Ethereum2 | 0871c3fed47b085e509189abc50455111483a7b1e6b71d339d2d47cf5236c82b |
| Ethereum3 | f56b07c4752b289edb7fe06750bf0086239a8f807a59022365f4eb2b6e487343 |

回滚需先暂停Relayer，逐组件停止后恢复该组件自己的备份插件，再启动PTC/PS和原Relayer。
PTC与PS原Ethereum3版本不同，不能混用备份。不要清理bridge_tx_记录或已背书UCP；
已经提交的目标交易仍按原哈希对账，不重新发送源业务。

## 验收记录

2026-09-04：代码0840fbd已部署；原93条正在按正常流程继续验证与执行。
原96条UCP的raw_message SHA-256已在部署前保存，最终应逐条比对不变。
最终结果以工作区完整验收证据和后续文档提交为准，不把“PROVED”当成目标执行完成。
