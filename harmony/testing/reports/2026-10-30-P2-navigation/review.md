# P2-03c · 鸿蒙原生导航与系统返回验收

日期：2026-10-30。分支 `codex/harmonyos-kuiklybase`，基线 `747939e8` 加当前未提交实现；关键文件见 [源码哈希](source-sha256.json)。本轮单 agent 执行。

## 实现

使用 `Navigation + NavPathStack + NavDestination`，首页为根页，聊天、待处理、设置及其子页、主机列表／认证／编辑、工作区／目录分别入栈。保留系统默认转场，未添加自定义全屏 Pan／Swipe 手势。工具栏返回调用同一个原生栈，系统返回由框架处理；键盘和抽屉优先关闭。

典型路径：`首页 → 设置 → 主机管理 → 编辑`；依次返回 `主机管理 → 设置 → 首页`。待处理按实际入口返回，不再根据是否存在已选会话猜测来源。选择主机、成功配对、选择工作区仍按业务语义回首页；切换会话复用唯一聊天目的页。

原生返回完成后从真实栈同步当前路由；认证页实际移除时才取消在途连接。主机编辑保存后返回原列表，改变地址后的重连保留原列表来源。

页面出栈后允许框架销毁实例；草稿、已加载历史和阅读位置继续归属 `HostRuntime`。重新打开当前会话不重复订阅，避免清空分页历史；聊天新实例初始化阅读状态，恢复保存的偏移。此处是内存状态，不包含杀进程持久化。

## K 与构建

- [核心检查](core-tests.log)：25 项 Kotlin/JVM 测试结果可用，本轮任务为 UP-TO-DATE；[JUnit 结果](core-jvm.xml) 为 25／0 失败。20 项 Node／ArkTS 核心测试本轮执行全部通过，其中新增 5 项导航策略测试。
- [Native 与正式 HAP 构建](build.log) 成功；未改 Kotlin Native 源码，publishNative 为 UP-TO-DATE，HAP 实际重新编译。产物见 [SHA-256](hap.sha256)，为未配置真机签名的调试 HAP。
- Python AST 检查和 `git diff --check` 通过；生产 Index 与专项临时工程逐字比较，差异仅为下述诊断节点。

## F · 模拟器实际系统手势

环境：Mate 70 Pro ARM64，API 21，1316×2832px，HDC `127.0.0.1:5559`。仅安装独立包 `com.clarklevis.whaleness.harmony.smoke`，本机夹具 18783／18784，延迟配对夹具 18785。未覆盖正式包，未重启用户 3080／3081／3082 服务。

命令：`python3 harmony/testing/product_ui.py --scenario native-navigation`。最终完整运行 [7 项结果](results.json)、[操作及真实栈记录](native-ui.log)、[独立包构建](smoke-build.log)。临时工程在导航外部注入 1vp 高的栈诊断文本；正式源码／HAP 不含此节点，并已读取正式 HAP 的 `ets/modules.abc` 确认不存在诊断 ID。状态断言读取实际 `NavPathStack.getAllPathName()`。

| 实测项 | 结果 |
| --- | --- |
| 设置 → 字号，右边缘回设置，左边缘回首页 | 栈从两层依次到一层、零层 |
| 8px 短边缘尝试 | 字号页和在途认证页均不出栈；认证按钮继续显示连接中 |
| 设置 → 主机列表 → 认证 | 左右边缘逐级返回实际设置来源 |
| 在途认证出栈 | 45 秒延迟配对到达后仍无新增主机、无迟到激活 |
| 聊天出栈、重新进入 | 草稿保留；分页旧消息第 13 条纵坐标 706px → 706px，偏差 0px |
| 聊天抽屉打开时边缘返回 | 仅关闭抽屉，聊天目的页和草稿保留 |
| 编辑主机 | 侧滑放弃未保存更改；保存回列表，再依次回设置、首页 |
| 待处理入口 | 聊天入口回聊天；首页入口回首页，即使内存中仍有已选会话 |
| 工作区 → 目录 | 边缘返回工作区，工具栏再返回首页 |

本轮另有 HDC 系统 Back 键独立探针：字号页原生栈 `settings|settings/font`，发送 `uitest uiInput keyEvent Back` 后为 `settings`。所有输入步骤也在隐藏输入法后断言原页面仍在。

[返回前阅读位置](native-reading-before.png)／[重入后阅读位置](native-reading-after.png)／[数值锚点](reading-anchor.json)；[取消认证后](native-auth-canceled.png)；[最终首页](native-navigation-home.png)。这些是 F 截图，不作为全量 V 对照。

设置回归：`python3 harmony/testing/product_ui.py --scenario settings` 完整结束，深色／英文／150% 杀进程恢复、恢复中文与浅色、系统字号开关、隐私、关于及逐级工具栏返回全部通过。见 [设置结果](settings-results.json)、[操作记录](settings-ui.log)。

## 注入方式排查与边界

普通 UITest 应用级 swipe 和上半屏探针未触发系统返回，因此改用 Input Kit `uinput -T -m` 在下半屏注入。一次 400ms 测试滑动被系统识别为侧边栏停留手势，日志显示 `SCBGestureDock` 出现，没有应用崩溃记录；最终改为 160ms 普通返回滑动，完整专项通过。未添加应用侧补偿手势，也未更改设备系统设置。

短滑测试证明低于阈值时未出栈，不等于已录制完整交互动画的中途撤销过程。宽屏、跨会话锚点、完整键盘／弹层组合、真机手势和 P2 全量视觉验收仍由原任务保留未完成。

SDK 依据：[Navigation](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkui/arkui-ts/ts-basic-components-navigation.md)、[NavDestination](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkui/arkui-ts/ts-basic-components-navdestination.md)、[系统触摸注入](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/dfx/uinput.md)。
