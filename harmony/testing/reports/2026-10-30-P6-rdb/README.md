# E18 · 真实 ArkData 加密 RDB 专项

2026-10-30，API 21 ARM64 模拟器，独立 smoke 包。正式包与用户网关未改动。

## 通过范围 F

实际 SDK 使用 SecurityLevel.S2、encrypt:true，迁移 v1 到 v3 后保留原草稿；验证主机隔离与草稿版本、中文／英文与字面通配符搜索、窗口／目录／索引同事务回滚、清理 epoch 拒绝旧写入、关闭重开持久化、移除主机 tombstone 不影响另一主机、真实统计。见 results.json、rdb-probe.png。

适配选择：当前独立 KBA 构建仍排除 Room 实现；磁盘使用真实 ArkData，缓存窗口、格式与网络接管沿用共享 Codec／HistoryStore，不提供空数据库。接口为串行异步事务。

## 边界

本专项只验证真实数据库适配；事务数据为自建样本，不代表真实 Host G、产品杀进程恢复或真机 D。SDK 加密配置已实际运行，未进行文件级密码分析。产品持久化与离线交互仍继续验收。
