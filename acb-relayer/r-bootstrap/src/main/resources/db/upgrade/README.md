# 数据库升级

已有数据库不会重复执行 `ddl.sql` 中的表定义。升级中继服务前，请按版本顺序手动执行本目录中的 SQL 脚本，并在执行前备份数据库。

## 2026-08-20

执行 `20260820_anchor_process_lane_index.sql`，使不同 TPBTA lane 的锚定任务能够分别保存进度。

## 2026-10-12

执行 `20261012_expand_validated_consensus_hash.sql`，将验证共识状态的区块哈希列扩展到 128 个字符，以兼容 Dioxide 等非 32 字节区块哈希。
