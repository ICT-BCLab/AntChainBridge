# my02 → eth04 的 408 / 404 修复与操作说明

2026-09-20 已修复并部署独立发送、回执查询工具。故障来自合约 Identity 被当作名称再次哈希；
源链交易校验失败，未进入跨链流程。修复后的新消息源链执行成功，监管批准，eth04 业务正文精确接收一次。
最终确认仍在等待 eth04 的 finalized 区块覆盖目标交易，下文分别记录执行和确认结果。

## 根因与修改

- **408 是 Mychain SDK 校验错误。** 现场 SDK 的描述是 `transaction verify failed`，并非 HTTP 超时。
  发送工具把已知合约的 64 位 hex Identity 传给 `callContract(String, ...)`；这个重载接收合约名称，
  会调用 `Utils.getIdentityByName` 再次哈希。正确地址存在 EVM 合约，再哈希地址不存在合约。
- **404 中的默认 receipt 不能当作成功。** 截图哈希查询外层响应失败、块高为 0；SDK 仍提供默认
  `receipt.result=0` 对象。旧工具只判非空，错误输出 `NATIVE_RECEIPT_FOUND=True`。
- **工具使用正确类型并保留一次发送记录。** 新发送工具使用 `Identity` 重载，预先确认源 EVM 合约存在；
  新查询工具同时检查外层成功、有效块高与 receipt，区分查不到、待确认、执行成功和执行失败。
  `SEND_ONCE` 要求新的持久化尝试文件；已有文件会在提交前拒绝调用，不自动重发。

| 地址含义 | Identity |
| --- | --- |
| 正确源业务合约 | `2c8363f71d100ad44ff3ef2e8dba3e333a0df426d7200a3b9fb39913f601c3db` |
| 被再次哈希的错误地址 | `f9b9e2f09e3cbfb1090b7ec97d78cf810fdf8b81ab30ca67c4dc593a27f0fee1` |
| eth04 接收业务合约 | `0x53b0b7631c8e01c0f1a711d94069d292f2199fdc` |

## 服务器使用方法

在 Relayer 服务器执行；配置和签名材料继续使用原服务器文件，不需要复制到本地。

```sh
release=/root/workspace2026/mychain-identity-recovery-20260920
mychain_config=/root/workspace2026/mychain-monitor-flow/my02-flow.json
mychain_java=/root/jdk8u402-b06/bin/java
source_identity=2c8363f71d100ad44ff3ef2e8dba3e333a0df426d7200a3b9fb39913f601c3db
target_identity=00000000000000000000000053b0b7631c8e01c0f1a711d94069d292f2199fdc

# 只检查源合约，完全不发送交易。
"$mychain_java" -cp "$release/build:$release/mychain020-sdk.jar" MychainExistingSender \
  "$mychain_config" "$source_identity" eth04 "$target_identity" preflight CHECK_ONLY

# 查询截图中的原哈希；应显示 NOT_FOUND / NATIVE_RECEIPT_FOUND=False。
"$mychain_java" -cp "$release/build:$release/mychain020-sdk.jar" MychainReceiptQuery \
  "$mychain_config" b2037b874b50aa14133ac32e5c2289828a8ece170369706fcb95e3f7770b4144
```

发送**全新业务消息**时，在上面环境中设置正文及该业务请求固定的尝试文件，首次提交使用：

```sh
message='替换为本次全新业务消息'
attempt_file="$release/替换为本次业务请求唯一编号.attempt"
"$mychain_java" -cp "$release/build:$release/mychain020-sdk.jar" MychainExistingSender \
  "$mychain_config" "$source_identity" eth04 "$target_identity" "$message" SEND_ONCE "$attempt_file"
```

同一业务请求必须保留同一尝试文件。结果未知时查询原哈希，不删除记录或换文件重新提交。
不要执行历史 `accept_canary.sh` 或把旧运行的消息正文当作新消息重发。历史六参数 `SEND_ONCE`
现在会要求补充尝试文件，这是有意增加的防重复提交检查。

## 发布与回滚

- **源码：** 本仓库 `acb-sdk/tools/mychain-cli/`；运行修复提交 `47ac088`。
- **依赖：** 使用 Plugin Server 已运行的 Mychain020 插件 JAR，其 Identity 重载经过真实调用验证。
  SHA-256 为 `d2a3d15c81490b34efb80e5716e517474964cd4b38c9bae4ca61d7ad90dc905a`。
  旧独立 JAR 只有 String 重载；不能只替换工具 class 而继续使用那个旧 JAR。
- **已更新位置：** `mychain-monitor-flow/mychain020-bbc-1.0.0-SNAPSHOT-plugin.jar`；
  `r-cli/evidence/T33-EXISTING-SENDER-DISCOVERY/existing-sender-tool/` 的发送与查询源文件/class；
  `r-cli/evidence/T33-MY02-TO-ETH04-20260920-144727/mychain-receipt-query/` 的查询源文件/class。
  上述相对路径均位于 `/root/workspace2026/`。
- **回滚：** `$release/backup/manifest.json` 记录每个被替换文件及原文件备份位置；按清单恢复原存在文件，
  仅移除本次新增的工具文件。保留尝试记录、诊断和历史交易。旧工具存在已知错误，恢复后应停止使用其发送能力。
  本次未重启核心服务，也未修改合约、ACL、监管路由或确认门限。

## 验收记录

- **本地逻辑：** Java 8 编译通过；真实 SDK JSON 解码的 9 项回执回归通过。
- **现场前置检查：** 正确 Identity 返回 EVM；错误再哈希 Identity 返回空。发送工具的有效 Identity 与预期一致。
- **旧请求：** `b99c1080…deb1d`、`b2037b87…b4144` 在源链无成功回执、Relayer 无对应 UCP；历史记录保留。
  截图哈希用修复后工具查询为 `NOT_FOUND / FOUND=False`。
- **新源交易：** `ab2c52cdb7fcaea94ad656c9cda6298e981310870dac1207ddaee387b23853ea`，
  块高 `26146334`，`confirmed=true`、`success=true`、`result=0`。
- **新 UCP：** `87e07933e38d5146e48f2a0d4b20539a55e6aba1daedb86439d4219ed344683e`，
  `PROVED`、监管 `APPROVED`。
- **新目标交易：** `0xb66ca82b73cd97c019f172f5731c7a046fb20f5a3948074deb73f8b355f7eb0f`，
  块高 `1690034`，原生 receipt status=1；源域、源身份、无序类型与正文完全匹配的业务事件恰好 1 条。
- **最终确认：** 待 finalized 覆盖块高 `1690034`，再验收 Relayer 终态和监管第四阶段；不以已执行代替最终确认。
- **重复防护：** 同一尝试文件再次调用在提交前返回 `FileAlreadyExistsException`，没有广播，原尝试内容不变。
- **公网接口：** `/api/overview` 返回 HTTP 200，严格 TLS 校验成功。

服务器 `$release/` 保存精简验收结果、编译结果、原生回执、重复防护结果和备份清单。
原先另一个新部署发送合约的 ACL 拒绝与本次 Identity 错误是不同故障；本次没有扩大 ACL，
也没有修改 9 月 14 日那笔历史监管 ERROR。
