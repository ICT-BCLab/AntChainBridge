# 普通 Dioxide 的本地功能测试接入

产品 `dioxide` 使用普通 AM／SDP，不部署 Monitor 或 PTC Hub。接收端保留原有受信 Relayer 模式；不能把本轮功能测试结果解释为链上背书证明或共识密码学验证通过。

## 配套组件

- 普通插件提供真实共识状态读取，保留链上原始哈希字节。预部署绑定一致时跳过配置交易，冲突时拒绝改绑。
- PluginServer 的共识读取不再以 PTC 接口版本为门槛。PTC 接口仍保留原有版本检查，不伪造普通插件的 PTC 能力。
- Relayer 为普通 Dioxide 收集源共识状态；存在 BTA 时按源背书通道维护通知游标。是否有接收端 PTC Hub 与此分开处理。
- 委员会、监管节点对 `dioxide` 和 `dioxide2` 沿用现有 Dioxide 功能兼容分支，跳过 HCDVS 验证。其他产品不使用该例外。
- 两节点对两个 Dioxide 产品保存 SHA-256 查询键，`raw_vcs` 保存完整原始状态；表结构保持 `varchar(64)`。

## 使用约束

本轮仅更新本地 antTest。FISCO 的 Base 和监管 BBC 必须在不同的 PluginServer JVM 阶段运行，停 anchor 不能替代重启 JVM。保留原 Dioxide 链数据、checkpoint、协调 namespace 和历史记录。

每笔源业务调用前保存独占 attempt，结果不明时只查原哈希或 operation。注册流程跨 Relayer 与 PTC 数据库，不具备跨组件原子性；失败后必须分别核验各组件已产生的 BTA／TpBTA，不能仅依据 Relayer 事务回滚判断尚未注册。

离线回归覆盖共识读取、幂等绑定、产品隔离、摘要读写、V0/V1 共识接口兼容及 Relayer 源共识采集。真链测试记录保存于 antTest 的独立 test-results 目录，不计入论文实验。
