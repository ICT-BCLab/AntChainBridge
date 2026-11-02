# 本地离线验收报告（2026-10-19）

## 本轮范围

本轮完善实验工具，不修改 Relayer 调度、链上合约、数据库或远程服务。未连接实际区块链、未部署合约、未签名／广播真实交易。真链六方向的 Base／Xun 预实验另行安排。

实验设计已改为每个方向的每种方案 **5 轮**，共 **60 轮、9,000 条计划样本**；正式轮仍为 5 TPS、30 秒、150 条、512B、unordered SDP V1。单次发送命令仍执行一轮。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Java 21 离线 Maven reactor 测试与完整 package | 通过，29 项测试，无失败、错误或跳过 |
| 开环发送／运行登记与中断保护 | 11 项通过 |
| 接收 ABI 解码、完整扫描、RPC／回退及部分证据保护 | 7 项通过 |
| Mychain 名称／Identity、应用不存在、本地模拟失败及原始身份调用 | 7 项通过 |
| 既有 FISCO 选择与验收工具测试 | 4 项通过 |
| Python 严格聚合 | 19 项通过，包含多场景子测试 |
| Python 静态检查（Ruff F）与 Git whitespace 检查 | 通过 |
| 独立工具 JAR 启动检查 | 正常返回用法错误退出码 2，无 SDK 初始化 |
| Java → Python 文件格式闭环 | 150 条模拟样本通过协议与应用两层验收 |
| 完整矩阵聚合 | 60 个唯一轮、9,000 条模拟样本、12 个分组均按每组 5 轮完成 |

以上 **48 项单元测试**通过。跨语言闭环与完整矩阵均使用显式标识的 `SYNTHETIC`／`synthetic` 数据和模拟时钟，不是论文正式数据，也不能证明真链吞吐或链 SDK 运行环境已验收。

运行命令：

```shell
mvn -o -f tools/paper-experiment/pom.xml package
python3 -B -m unittest discover -s tools/paper-experiment -p 'test_*.py' -v
ruff check --select F tools/paper-experiment/aggregate_latency.py tools/paper-experiment/test_aggregate_latency.py
git diff --check
```

跨语言夹具 `OfflineScenario` 位于 common 的测试源码中，不进入发布主类。它使用 Mock sender、合成原生收据与模拟观察时钟生成真实落盘的 v2 文件，然后交由 Python CLI 验证；模拟数据目录还具有 `SYNTHETIC-NOT-PAPER-DATA` 标识。不能把该生成器用作真实链发送器或正式结果来源。

## 关键覆盖

- 同一记录重复 150 次、重复输入文件、缺失／额外样本、索引缺失、源交易／UCP 一对多与多对一、跨链指标冲突、完成标记不匹配均不能通过。
- 相同指标／同一个物理事件的重复读取不误报；不同事件位置或不同交易中的重复业务接收会失败；一个目标交易可以承载多个不同样本。
- 运行目录独占、持久化逻辑轮登记、并发竞争、换目录重跑、意图落盘失败、结果不明、已有哈希失败、模拟失败及日志尾部截断。
- 使用具备发送方法的 spy reader 验证 reconcile／接收采集没有广播；无法确认的原交易保持 UNKNOWN，不续发、不补造时间、不改写原始记录。
- 150 条内容匹配、少一条、重复一条、载荷字节损坏、来源／应用／链错误、错误顺序标志、原生回执失败、未确定、观察不足、RPC 错误与区块变化。
- 历史平台上报成功日志不能充当实验时间埋点；显式 legacy 导入永远不产生新版有效轮。

## 解释范围与待真链验证项

- t0–t3 保持原定义；t1 仍为 UCP INSERT 返回后的埋点，不是数据库 COMMIT 时间。
- 应用“恰好一次”只覆盖保存的确定区块观察区间。旧 Mychain amNotify 不独立携带有序标志，其无序前提明确来自固定的源端 unordered V1 调用及原收据关联，并以 orderEvidence 字段区分。
- 三种链 SDK 的 RPC／ABI 兼容性、真实部署身份及绑定、最终性接口、时钟偏差和持续注入能力仍需在后续小规模真链预实验确认。
- 构建使用本机已有离线 Maven 依赖。既有 bid-sdk 的 systemPath 告警和合并 SDK 时的重复资源／module-info 告警未造成构建失败；本轮没有做干净主机依赖重建。

## 历史记录

2026-10-09 的报告记录过 Relayer 指标 5 项、监管模拟器 6 项离线测试，以及 FISCO Base／Xun 的少量真链功能闭环。这些历史验证不能计为本轮新的真链测试，亦不能替代本次 60 轮正式实验。原有真链记录仍保留在 antTest/test-results 中，本轮未修改。
