# 2026-09-04 部署与验收记录

## 结论

Dioxide ISN 修复已部署；**完整验收矩阵尚未全部通过**，独立的 Ethereum/PTC 日志索引错误
阻塞了普通突发测试中的 93 条源消息。没有重发这些消息、强制 PROVED 或绕过 PTC。

## 本轮已通过

- Mychain→Dioxide 监管：三批各 32 条，加额外跨插件竞争 32 条，共 128 条全部闭环，
  每条业务正文只执行一次；PTC 100%、监管四阶段完整。
- Mychain 冒烟一条；普通 Ethereum 入站三条；Dioxide 普通/监管反向各一条。
- 两插件交错分配：ISN 306=diox11、307=diox04、308=diox11、309=diox04。
- 生产协调库总共 134 个 ISN（181–314）全部唯一、FINALIZED，无 ISN abort/UNKNOWN/FAILED。
- Python 同一 operationId 再次调用复用原始哈希，数据库只有一条提交记录。
- Ethereum→FISCO SDP V1/V2/V3 均成功，PTC 100%、监管四阶段完整。
- 两条 Dioxide 锚定及现有 Ethereum/FISCO/Mychain 锚定 RUNNING。Portal、Runner、Data API、
  Mychain 查询适配器和三条隧道 active；公网 Overview/Statistics/UCP/Dioxide 交易查询 HTTP 200。

监管突发 batch1/2/3 源链整批耗时 1202/758/823ms；目标首条分配至最后 journal FINALIZED
为 38.19/34.38/449.69 秒。第三批包含回执查询修正和插件切换，不能计为正常稳态性能；
链上业务已经执行一次，仅恢复正常查询归档，没有重发。额外竞争批次目标阶段 40.07 秒。

离线测试：Java 8 协调 8 项，Python 10 项，两插件各 14 项，Runner 4 项；通用上游插件 7 项。
包括两 Java/两 Python 的 128 次并发分配、签名前后退出恢复，以及跨语言查询 mailbox 32 次隔离。

## 代表 UCP

- 冒烟：`909f29f4f0d434bbae533c5f73b73c89170b2e2983989fe811df1ac7c34ad0eb`
- 普通反向：`45982472456ccd640eed4a64fe412755c6844a69a47eeefa3e93f2d085baa794`
- 监管反向：`65863787379ca926db3e828bca469b708d0005638db11284668eb0b52280ce93`
- Ethereum→FISCO V1：`e832f98a3bffc4c865c8ee31e44deea51d3b08faa16c3e7071da46c8f52aad18`
- Ethereum→FISCO V2：`e289e5876e07635ceed32c07167c139173a6539ca1ef926815f0d8120dd31fc6`
- Ethereum→FISCO V3：`d3976ed57a162a3c18cd7f54612ab3be115f1e567cb1d6ad7120e2e0c828f175`

## 独立阻塞与边界

普通 Ethereum 三批源交易均成功，但每个区块仅首条通过 PTC。第二条代表 UCP
`2fc32b4024cc8ab23959d321a6d16c1647d21033a97a70307c88162f595126cb` 的存储
ledgerData.logIndex=2；它的实际 receipt 只有两个日志，合法局部下标 0/1、区块全局索引 2/3。
Ethereum2/Ethereum3 采集端使用区块级 logIndex，HCDVS 却按 receipt 局部索引读取。
93 条尚未进入 Dioxide，不能归因为 ISN，也不能标为通过。扩展采集插件/PTC 验证器的修复
和部署范围已请求用户确认，当前不修改其证明或状态。

my02 现有测试应用处于全局监管模式，未为测试关闭其他业务的监管。Dioxide 原生 SDP 是 V1；
AppContractV2 是业务存储版本，不是 SDP V2。普通和 V2/V3 的替代测试矩阵待用户确认。

历史失败 UCP `ea0e456b…23fdf5` 不补发；旧 AuthMsg lambda9 异常另列诊断，不混入本次成功率。

## 上线版本

- Relayer SHA256 `13fef62428f9ab6c48cae5749a5404e69cb52bd85fefee7fd66b9cbadbaf470f`
- Plugin Server SHA256 `0118f771dbfba9ca9e5cbea9a57ae9a24aa9d6b52157e50d22a207effab78e37`
- dioxide（源码96adb49）SHA256 `11f0c6c5a649cbbc2789e8aa600ab965a41b29bba458d2b340d2573792ccb7ad`
- dioxide2（源码96adb49）SHA256 `3c6b6a6e679e9f06fe82792d3946bd7fc1c8a01c68c76343fec927a83e15aef3`
- Python coordinator `c9849d0`，crosschain adapters `0eec213`、部署助手 `68355fe`。

链上合约、账户/CID、公网端口和前端均未改变。备份包和协调记录保留；回滚不得删除签名记录
或回退 ISN。Runner 停止会连带停止 Backend，恢复必须显式启动两者。

PR：[ICT #6](https://github.com/ICT-BCLab/AntChainBridge/pull/6)、
[通用上游 #83](https://github.com/AntChainOpenLabs/AntChainBridge/pull/83)、
[Runner #3](https://github.com/ICT-BCLab/crosschain/pull/3)。均 Draft，不 force-push、不合并。
