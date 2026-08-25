# SDP V1/V2/V3 监管兼容与部署说明

## 1. 目标与范围

本次升级解决“发送端经过 Monitor 封装，目标链 SDP 却直接把监管信封交给业务合约”的不对称问题，并将 Ethereum、FISCO BCOS 与 Mychain 的监管路由从 SDP V1 扩展到 V2/V3。

兼容原则如下：

- 普通模式与监管模式使用同一套 SDP V1/V2/V3 接口；
- Monitor 在发送侧封装，在接收侧解封后才调用业务合约；
- request/response 以及 ACK 路径使用相同的监管规则；
- 目标业务合约收到的 `bytes` 必须与源业务消息逐字节一致；
- 原生协议只实现 SDP V1 的链不伪装成 V2/V3 支持。

## 2. 当前能力矩阵

| 链插件 | 普通 SDP | 监管 SDP | 接收侧 Monitor 解封 | 备注 |
| --- | --- | --- | --- | --- |
| Ethereum3 | V1/V2/V3 | V1/V2/V3 | 支持 | Monitor V5，SDP 保留 V1 兼容接口 |
| FISCO BCOS 3.x（`fiscobcos`） | V1/V2/V3 | V1/V2/V3 | 支持 | Monitor V5，与 Ethereum3 复用同一套合约源码 |
| FISCO BCOS 2.x（`fiscobcos3`） | V1/V2/V3 | V1/V2/V3 | 支持 | Monitor V3，使用 0.4.25 编译器生成双密码套件 wrapper |
| Mychain 0.20 | V1/V2/V3 | V1/V2/V3 | 支持 | Monitor V6，包含安全变长字节解码 |
| Dioxide | V1 | V1 | 按 V1 路径 | 链原生 SDP 协议当前未定义 V2/V3 |

## 3. 调用路由

监管开启时，完整路由为：

```text
源业务合约
  -> 源链 Monitor 封装
  -> 源链 SDP V1/V2/V3
  -> AuthMessage / Relayer / PTC
  -> 目标链 SDP V1/V2/V3
  -> 目标链 Monitor 校验并解封
  -> 目标业务合约
```

监管关闭时仍经过相同的 Monitor 路由，但信封中的控制类型为 `MONITOR_CLOSE`；这样不会因两端策略切换而让业务合约收到封装层。

## 4. 本次修复的根因

### 4.1 V2/V3 接收端绕过 Monitor

旧实现只在 V1 路径上完成 Monitor 转发，部分 V2/V3 request 和 ACK 入口会直接调用业务合约。修复后，三个 SDP 版本的发送、接收与 ACK 均通过 Monitor 的对应入口。

### 4.2 多 EVM 编译器的非 32 字节对齐 payload 错位

旧版 Ethereum、FISCO 和 Mychain Monitor 使用的汇编变长字节解码会随标准 solc、FISCO solc、MySolidity 的内存对齐差异产生不同结果。典型现象是 36 字节业务消息只剩尾部片段和大量 `0x00`，但交易回执仍然成功。

Ethereum/FISCO Monitor V5、旧 FISCO Monitor V3 与 Mychain Monitor V6 均依照旧 ACB EVM codec 的“逆序 32 字节块 + 末尾长度”格式显式计算边界，再逐字节恢复逻辑内容，避免依赖编译器敏感的内存拷贝。各链插件的期望实现版本也同步提高，`setup-bbccontracts` 会检测旧运行码；支持原位升级的链保持原地址，不支持安全原位升级的插件会部署新 Monitor 并把新地址写回链上下文和业务测试合约。

### 4.3 Mychain PTC Hub 保留了旧 BCDNS 根

EVM 运行码升级只替换 code，不会再执行构造函数。因此旧 PTC Hub 升级后仍可能保留历史 BCDNS 根，随后验证新 TPBTA 时报 `issuer not found`。

PTC Hub V2 增加 owner-only 的根证书对账入口；Mychain 插件在可重复的 `setup-bbccontracts` 流程中探测旧运行码、必要时升级，并用当前配置的 BCDNS 根执行对账。

### 4.4 重复 setup 降级 SDP 就绪状态

旧 `setupSDPMessageContract()` 在合约已经完成绑定时仍把聚合状态写回 `CONTRACT_DEPLOYED`，使后续任务判断为“SDP not ready”。修复后，重复 setup 保留 `CONTRACT_READY`。

### 4.5 替换 Monitor 时丢失既有监管节点背书

MonitorVerifier 的 `monitorNodeEndorseInfoMap` 在 TPBTA 写入 PTC Hub 时同步生成。旧 FISCO
升级流程在替换 Monitor 时同时部署了一个空 MonitorVerifier，再让已有 PTC Hub 指向它；新
Verifier 虽然地址双向绑定正确，但没有历史 TPBTA 的监管节点背书，所有监管消息都会在目标链
以 `MonitorVerifierMsg: no monitor node endorse info` 回滚。

修复后的升级流程仅替换存在问题的 Monitor，并复用当前 PTC Hub 已绑定的旧 MonitorVerifier，
从而保留既有背书状态。已产生空 Verifier 的环境必须在审计后将新 Monitor 和 PTC Hub 同时绑定
回旧 Verifier；只修改其中一侧会留下不一致状态。

## 5. 部署与升级顺序

1. 备份当前插件 JAR、Relayer 链配置与合约地址。
2. 核对 `bcdns_root_cert_pem` 与当前 BCDNS/PTC 证书的 issuer owner。
3. 替换插件 JAR，先重启 Plugin Server，再确认 Relayer 恢复对插件的连接。
4. 对目标链执行一次 `setup-bbccontracts`；根据插件能力原位升级旧运行码，或部署新 Monitor 并重建绑定。不要假设所有链的地址都会保持不变。
5. 读取 Monitor/SDP/PTC 实现版本与绑定关系，再发起新的链上验收交易。
6. 分别使用监管关闭和监管开启模式发送 V1/V2/V3，同时验证源交易、Relayer 归档、监管结果、目标交易和业务 payload。

不要只以“目标交易 success”作为验收标准；必须读取目标业务合约的事件或状态，确认 payload 没有 Monitor 外层且逐字节一致。

## 6. 验收查询

大屏数据 API 可用于核对 UCP 生命周期与监管上报：

```bash
curl -k 'https://47.94.7.98:18443/api/data/messages?page=1&size=20&sourceDomain=eth04&targetDomain=my02'
curl -k 'https://47.94.7.98:18443/api/data/message/{ucpId}'
```

单条详情中至少应看到：

- 源链与目标链交易哈希；
- `monitor.reportStatus=REPORTED`；
- `monitor.status=APPROVED`（对允许通过的测试样例）；
- `monitor.currentStage=TARGET_EXECUTED`；
- `monitor.completeness` 中四个阶段均为 `true`。

## 7. 回滚

- 应用回滚：恢复备份 JAR 并重启 Plugin Server/Relayer；
- 配置回滚：用 Relayer CLI 恢复备份的链配置；
- 链上合约不删除；如需恢复旧运行码，必须使用已审核的旧 runtime 并保留原地址/存储布局。
- 回滚后重新执行最小 V1 普通与监管双路径验收。

## 8. 已知边界

- Dioxide 当前只支持原生 SDP V1，本次不通过改写版本字段伪造 V2/V3。
- Mychain Maven 单测在没有私有 GitHub Packages 凭据时会因 Mychain SDK `401` 无法解析；发布时仍需使用生产插件依赖完成 Java 编译，并使用官方 MySolidity `0.8.14` 生成部署/runtime 字节码。
- 历史失败队列与本次新发验收交易分开统计，不应用存量失败掩盖或否定新路径的结果。
