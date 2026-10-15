# 本地测试报告（更新于 2026-10-09）

## 范围

论文实验测量代码只执行 Mock/模拟时钟单元测试和本地编译，未启动 Relayer、数据库、
Plugin Server 或 Monitor Node，也未生成正式论文实验数据。FISCO 插件拆分另使用本机
FISCO BCOS 3.11.0 标准四节点链执行了低写入量真链验收；只部署测试合约和发送少量
验收交易，结束后已停止节点。

## 结果

| 范围 | 结果 |
| --- | --- |
| Relayer指标定向测试 | 5项通过，0失败 |
| SimpleMonitorSystem | 6项通过，0失败 |
| 开环负载工具 | 3项通过，0失败 |
| Python聚合器 | 5项通过，0失败 |
| Ethereum/Mychain/FISCO发送适配器 | 全部编译通过 |
| Relayer bootstrap完整打包 | 成功 |
| FISCO Base（`fiscobcos`） | 9 项离线测试及 1 项真链闭环通过，插件打包和本地安装成功 |
| FISCO Xun（`fiscobcos3`） | 9 项离线测试及 1 项监管真链闭环通过，插件打包和本地安装成功 |
| FISCO Base/Xun实验选择 | 2 项通过，两个插件可同时进入适配器编译类路径 |
| FISCO监管验收工具 | 全部使用`fiscobcos3`制品编译通过 |

覆盖内容包括：指标开关、SOURCE_ACCEPTED入库顺序、t2预捕获和t3去重；监管延迟仅作用于verify；150个绝对deadline、512B唯一载荷、失败不补发、双主机时钟门禁；乱序、重复、缺失、失败、负时延、轮均值和pooled统计。

FISCO 拆分测试另外覆盖：Base ABI 保留完整 PTC 且不包含 Monitor 路由、SPI 监管接口为空实现、完整 ABI 分片拼接、旧/新 AM 事件载荷、显式账号配置、HCDVS；Xun 覆盖 Monitor V5、SDP V1/V2/V3 路由、MonitorVerifier/PTC Hub 绑定 ABI 和监管控制值。

真链 Base 闭环覆盖 AM/SDP/PTC/App 部署、PTC trust root/TpBTA 注册、出站扫描、
共识状态读取及带委员会证明的入站投递。真链 Xun 闭环覆盖
AM/SDP/Monitor/MonitorVerifier/PTC/App 部署和绑定、控制值 1/2/3、有效监管签名放行、
空签名拒绝并发送回滚、CLOSE 放行及出站读取。两套模块默认 `mvn test` 均为
27 项、0 失败、0 错误，其中 18 项服务测试在未显式启用真链开关时按预期跳过。

## 已知构建告警

Maven会尝试访问仓库既有的GitHub Packages地址并收到401，同时打印`bid-sdk`中非绝对`systemPath`告警。所需依赖已存在于本地仓库，以上告警未导致任何本次测试或构建失败；正式实验主机应提前准备相同依赖，避免实验期间访问远端仓库。
