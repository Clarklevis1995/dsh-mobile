# 鸿蒙客户端开发约束

- 始终使用中文沟通和编写工程说明。
- UI 使用 ArkUI 原生组件，平台网络和凭据存储由 ArkTS 实现。
- Kotlin 业务规则复用主仓库 shared/commonMain，不在 ArkTS 重写协议、Reducer、历史合并和流式状态机。
- KuiklyBase 使用 harmony 独立 Gradle 构建，保持主项目 Android/iOS 的工具链配置独立。
- Token 和设备 ID 只存系统 Asset，配对码不持久化，网络原文不写日志。
- 网络异步回调必须核对连接代次，用户主动断开后禁止自动重连。
- 变更后执行 scripts/build-harmony.sh test，并验证实际 Native 和 HAP 构建。
- 首版不启用 Room 缓存；禁止为了编译通过而添加空数据库或伪造共享引擎。

## 任务规划与进度维护

- 执行鸿蒙工作前先读 `../Docs/harmonyos-client-plan.md`，以其中任务 ID 和验收条件为准。
- 每完成且验收通过一项，在同一轮更新规划书的勾选及完成记录；代码完成但验证不足时不得勾选。
- 测试夹具、真实网关、视觉对照与真机验证分别记录，不相互替代。
- 默认单一 agent 执行，不使用子 agent，除非用户之后明确改变要求。
