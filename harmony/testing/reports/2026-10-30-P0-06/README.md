# 鸿蒙平台接口与依赖最小样例

环境：DevEco Studio 6.0.1.251、本机 HarmonyOS API 21、Mate70Pro ARM64 模拟器。样例独立包为 `com.clarklevis.whaleness.harmony.compatibility`，不覆盖正式包。源码见 [CompatibilityProbe.ets](../../compatibility/CompatibilityProbe.ets)。

| 项目 | 实际结果 | 限制／后续工作 |
| --- | --- | --- |
| Markdown | `@lidary/markdown` 3.0.5、实际解析到 `@cangjie-tpc/formula_hybrid` 1.4.0，HAP 编译及运行成功；标题、粗体、链接、Kotlin 高亮代码和表格实际显示 | 组件只支持 V2 状态管理，依赖字节码 HAR；产品级 `buildOption.strictMode.useNormalizedOHMUrl=true` 必须开启。尚未接入正式聊天列表，P3 需验证 V1→V2 边界、增量和滚动 |
| 文件 I/O | 应用 cacheDir 中真实创建、写入、关闭、读回一致 | URI 附件的授权、复制和持久化由 P5 继续 |
| 数据库 | `relationalStore.getRdbStore`，建表、写入、查询、校验及关闭成功 | 仅证明平台 RDB 可运行；共享 Room 数据库仍未适配，禁止将其标为离线缓存已完成 |
| Asset／偏好 | 真实 Asset 旧 alias→主机 alias 迁移，旧条目删除、偏好重载与重复 load 通过 | 使用样例专属虚构凭据；Token 和设备 ID 未输出到结果或普通偏好 |
| 图片选择器 | 系统 PhotoViewPicker 打开后取消，返回 0 项 | 未验证真实图片 URI、缩略图及附件发送 |
| 文件选择器 | 系统 DocumentViewPicker 打开后取消，返回 0 项 | 未验证真实文件、系统打开与分享 |
| ScanKit | API 21 HMS ScanKit 声明和 `startScanForResult` 编译、调用已验证 | 当前模拟器返回取消／不可用；未证明摄像头扫码成功，P5 必须补验证 |
| 录音 | `media.createAVRecorder` 创建为 idle，release 成功 | 未准备或启动麦克风录音，不能标为语音输入已完成 |
| 通知 | `isNotificationEnabled()` 调用成功，当前返回 false | 未申请授权、发布通知或验证后台投递 |
| 签名 | unsigned HAP 构建和模拟器安装成功 | 没有正式签名资料；真机调试与分发签名仍待验证 |

依赖依据：[HMarkdown 包仓库](https://github.com/lidary-byte/HMarkdown)、[OHPM 发布页](https://ohpm.openharmony.cn/#/cn/detail/@lidary%2Fmarkdown)。API 原型逐项从本机 SDK `openharmony/ets/api` 与 `hms/ets/api` 核对。`runtime.png` 为独立样例的真实模拟器画面，没有用户聊天数据。

P0-06 整项保持未勾选：扫码、实际录音、通知授权／发布及真机签名尚未形成完整最小样例证据。此报告不替代 P3、P5、P6 或 P8 的功能验收。

## 原生锚定菜单补充

API 21 的独立 `PreferenceMenuRow` 在组件 `build` 中把 `Button` 绑定到直接 `@Builder` 引用，已实际弹出菜单并通过外观／语言与冷启动测试。最初行内 Builder 样例未弹出，已替换；不把“编译成功”当作显示成功。[OpenHarmony 官方菜单控制说明](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-arkui/arkui-ts/ts-universal-attributes-menu.md)。原生选中图标和应用级颜色模式继续补充视觉验证。
