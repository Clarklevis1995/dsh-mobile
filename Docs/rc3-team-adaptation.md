# RC.3 团队兼容适配

本次修改团队只读展示及负责人关联，不新增 Team 开关 RPC。发送、历史和停止继续使用已有移动协议。

## 数据规则

- `agent-team-v1` 可用时，`enabled=true` 的空团队及仍有任务的关闭团队都显示入口；目录子代理继续受 `subagent-conversations-v1` 能力约束。
- 目录 `name` 优先显示，`label` 为描述；旧目录的单层显示可回退到 label/id，但身份使用 ID。
- 已加载的多层目录按直接父关系组成完整路径。根为 `lead`，首层不加前缀，未命名链路使用 ID。RC.1 名册中的真实名称保留为旧任务的身份，只有 label 的旧目录不会覆盖它。成员保留直接父 ID，打开子会话时仍由共享层附加真实地址。
- `identityName` 保存身份路径，`name` 保存显示名。旧 Swift 桥接快照缺少 identityName 时，兼容使用既有 name。
- 任务保留可选 `ownerId`。有 ID 只按 ID 匹配，未知 ID 不按同名成员回退；无 ID 才按完整身份路径唯一匹配。两字段都缺失才是未分配。未知负责人保留名称或 ID 文本。
- `null/unavailable` 继续保留上次可读数据并报告状态；完整空值替换旧列表，投影 failure 保留诊断信息。

## 回归入口

共享层：

```sh
./gradlew :shared:iosSimulatorArm64Test
```

团队相关测试集中在 `SharedTeamStoreTest`，覆盖 enabled 空团队、关闭后的历史任务、name/label 分离、ID 优先、未知 ID、未分配、同名分支、未命名链路、直接父地址，以及原有 RC.1 生命周期和重连替换。

iOS：

```sh
xcodebuild test -project DeepSeekHarnessMobile.xcodeproj \
  -scheme TeamCompatibilityTests \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=27.0' \
  CODE_SIGNING_ALLOWED=NO IPHONEOS_DEPLOYMENT_TARGET=17.0
```

独立 scheme 只运行 TeamIntegrationTests，避免构建无关的 UI 性能测试 target。命令覆盖最低版本仅用于验证，项目配置保持原样；现有其他单元测试源码使用 iOS 16.4 API，而测试 target 的最低版本为 16.1。

Android：构建 debug 应用和 androidTest，在模拟器执行 `TeamTaskCardDeviceTest`、`TeamMemberMenuDeviceTest`。卡片测试覆盖 ID-only 负责人、未知 ID 不跳转、展开/收起与负责人点击互不干扰、长路径以及 1.5 倍字号。

## 本次验证与边界

- 共享层完整测试 649 项通过；其中团队测试 24 项。
- iPhone 模拟器团队集成测试 14 项通过；iPad 模拟器额外通过 RC.3 页面尺寸及字体测试。
- iOS 测试生成手机/平板宽高、横竖屏尺寸及 1.5 倍应用字号截图；Android 手机和平板各通过 8 项设备测试，并检查长路径卡片截图。
- Android 编译成功。全局本地化检查被其他工作区改动中的中文字面量阻断，针对性构建通过 `-x :androidApp:verifyLocalizations` 继续。Android 全量单元测试共运行 302 项，有 4 项失败：MenuArchitectureTest 指向 WorkspaceChangesUi.kt，ConversationHistoryPresentationTest 有 3 项锚点索引失败；本次未修改相关实现。
- 尚未连接真实 RC.3 网关进行真机发送、停止及断线恢复联调；也未完成两端整个团队页面在所有尺寸下的像素与手势对照验收。截图及测试产物位于 /tmp，未作为应用资源纳入仓库。
