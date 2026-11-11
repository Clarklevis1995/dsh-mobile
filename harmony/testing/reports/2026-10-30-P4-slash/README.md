# P4 命令限定范围验收（E14）

2026-10-30，Mate70 Pro ARM64／API 21 模拟器，隔离 smoke 包；正式应用与用户网关未变更。

- K：服务端 UI 元数据驱动，输入／选项／立即执行、旧回执、跨会话、超时及草稿版本保护通过；核心检查 58 项 JVM、31 项 Node 全部通过。
- F：原生命令目录与中文参数执行、选项模型确认、重放旧确认保留新草稿，以及立即执行项按 Host submitRequest 发送通过。
- 修复：程序填充输入触发同值 onChange 时不再作为新编辑解释，避免取消已选命令的选项菜单。

详细结果见 [results.json](results.json)；截图为本目录 slash-*.png。HAP SHA-256：`131767cca37d9b4b038dfe1d8d49da56bcbd45cb4e9f1ff24cbaf54d76a8c331`。

此项使用隔离协议夹具，不替代真实 Host G、全主题 V、设备 D，以及所有父阶段门禁。
