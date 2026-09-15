# 历史会话 Unicode 解码失败修复

验证日期：2026-09-15。

## 根因

iOS 打开“写一段冒泡排序的代码，用 mar”会话时出现 `decode-failed`。捕获到的失败消息实际是 `sessions` 列表响应，长度 117935 字节；错误位于 `items[47].projections.values.turnOutline[1].response`。

Host 的 `dsh-session-turn-outline` 用 `slice(0, limit - 1)` 截断摘要，截断点落在 emoji 的 UTF-16 代理对中间，留下孤立的 `\ud83d`。JavaScript 能序列化这个值，Swift/Foundation 拒绝解码整帧。会话原始历史正文没有损坏；这与历史文件格式 v3 本身无关。

## 修改

- 网关新增 `lib/wire-json.mjs`，所有 WebSocket 响应统一经过 Unicode 序列化处理，仅把孤立代理项替换为 U+FFFD。有效 emoji、中文、字面量反斜杠转义、JSON 类型保持不变，不修改输入对象和磁盘历史。
- iOS 的 `JSONValue` 按容器形状解析，保留嵌套错误；解码失败提供字段路径和原因，不回显整帧。
- 新增网关序列化测试、真实 WebSocket 列表测试，以及两项 iOS 解码回归测试。
- 临时失败帧采集代码已移除，真实响应不纳入仓库。

## 验证结果

- iPhone 17 / iOS 26.5 模拟器 XCTest：167 项通过，0 失败、0 跳过。
- 网关完整 `npm test` 通过；补充实际 WebSocket Unicode 列表断言后，分发测试再次运行，124 项通过。
- 两份捕获的失败响应经过新序列化器处理后，原 Swift 解码器均成功解码。
- 使用 Computer Use 搜索并打开目标会话，历史正文、模型和权限选项正常显示；退出后再次打开仍正常，无 `decode-failed`。
- 本轮未修改 Android，共享网关输出修复对两端生效；未声称完成 Android 本次 GUI 回归。

修复截图：[历史会话恢复](test-evidence/2026-09-15/ios-unicode-history-recovered.jpg)。

## 本地应用范围

网关源码及本机 `~/.dsh/profiles/web/node_modules/dsh-plugin-mobile-gateway` 已应用修复，原运行文件备份位于 `/tmp/dsh-decode-recovery/runtime-backup`。核对原 PID 92996 的命令与工作目录后发送 SIGTERM，使用原命令 `dsh web` 在原目录重启，替代 PID 为 1299。

服务重启时出现一次连接中断提示，Host 初始化早期曾返回服务未就绪，随后会话查询恢复；这些与修复后的历史解码验证分开记录。

本次修改尚未提交或发布。Host 摘要截断实现本身尚未修改，目前由网关传输边界处理无效字符；其他未经过该网关的 Host 客户端不在本次修复范围内。
