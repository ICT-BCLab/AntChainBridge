# Dioxide2 共识哈希存储本地验收（2026-10-22）

代码基点：`813b342`，分支：`feat/paper-experiment-measurement`。

## 结果

- 委员会和监管节点仅对 BTA 产品 `dioxide2` 使用原始哈希字节的 SHA-256 数据库键；其他产品保持原值。
- 本地 `mysql-test` 的 `committee_node`、`monitor_node` 两表各保留 **64,313** 条记录，其中各 **39,945** 条 Dioxide 记录已转换，两表合计 **79,890** 条。
- 每表其余 **24,368** 条非 Dioxide 记录保持原值；全部记录的主键、域、高度、版本、`raw_vcs` 和时间字段未变。BTA 数据和索引未变。
- 四个字段均已由 `varchar(128)` 缩为 `varchar(64)`；原有 `utf8mb4_0900_ai_ci`、可空性、默认值保留。
- 两份完整压缩备份均已在隔离数据库恢复、逐行比对，权限为 `0600`。实际库转换前先完成隔离副本演练；转换后完整核验并再次执行 apply，修改数均为 **0**。

## 测试

| 验收 | 结果 |
|---|---|
| H2 + 实际 Mapper/Repository | 委员会 8、监管 8，通过 |
| MySQL 隔离库 + 实际 Mapper/Repository | 委员会 8、监管 8，通过 |
| MySQL 迁移故障及中断回归 | 6，通过 |
| 原有 Repository、共识／UCP、HCDVS 离线回归 | 委员会 14、监管 17，通过 |
| Java 21 可执行 Boot 包构建 | 两个组件通过，无 clean 或依赖升级 |

覆盖原始字节摘要向量、新旧记录混合读取、非 Dioxide 产品隔离、BTA 缺失／冲突／查询失败、原编码矛盾、多个候选拒绝、全批回滚、中断续跑、重复执行、日志失败禁止写入及 BTA 快照变化停止。

原有 JUnit 4 用例由 JUnitCore 显式执行，未将 Maven 默认的零用例结果计为通过。原有 HCDVS 测试缺少 demo 夹具：仅在证据目录组装现有 demo HCDVS 源码及测试 BBC 二进制，完成离线测试；没有修改或安装业务插件。

演练期间一次校验启动受 Maven 重编译临时移走 target/classes 影响而中断；仅隔离库已提交批次保留，实际库当时未修改。后续冻结维护工具运行目录，以新日志恢复演练，验证了幂等性。用户中断时，实际两库迁移已完成；续跑仅补齐测试、构建和最终只读核验，没有再次转换。

## 构建产物

| 组件 | SHA-256 |
|---|---|
| committee-node | `4a39ed1f7f162b9f62fec0b64d820336fd1bfe9194e0112a966666ccd9147a65` |
| monitor-node | `98803d24dc9b0da40e68f5bc34dfea5a57bd5462579b4a1addcd9355e6a9268a` |

产物位于各模块 `target/*-0.1.0-SNAPSHOT.jar`，确认包含 Boot JarLauncher，class version 为 65（Java 21）。

## 运行边界

运行目录 JAR 哈希与基线一致，未替换程序包。业务服务未启动；anchor 保留 **16 条 STOPPED、1 条历史 INIT**，没有 RUNNING 项。未向业务链发送交易、未推进既有消息、未操作远程系统、未推送代码或创建 PR。

**本地字段已缩短，启动 Dioxide 监听前需安装本次新构建的委员会／监管程序包。** 旧包仍尝试写入 104 字符原值，不能直接用于已缩短的表。

详细脱敏 JSON、CSV、审计日志、完整备份及恢复记录位于：
`/home/saraday/workspace/20260408/antTest/test-results/20261022-dioxide-consensus-sha256-150007`。

远程部署继续暂停；远程历史数据是否需要转换另行审计，本轮没有执行远程 DDL 或迁移。
