# 阶段 13 Android UI 对齐与验证

## 源码映射

| iOS 基准 | Android Compose 实现 | 对齐内容 |
| --- | --- | --- |
| `Views/RootView.swift` | `ui/DshProductApp.kt` | Workspace → Conversation → Settings 导航、转场和系统栏 |
| `Views/WorkspaceView.swift` | `ui/DshProductApp.kt` | 品牌头、Workspace、搜索、新建会话、最近会话、归档和 Gateway 面板 |
| `Views/ConversationView.swift` | `ui/ConversationScreen.kt` | 对话/轨迹分段、时间线、附件、控制菜单、输入器和分页 |
| `Views/HumanQuestionView.swift` | `ui/HumanQuestionCard.kt` | 单选、多选、自定义答案、逐题推进、取消和提交 |
| `Views/SettingsView.swift` | `ui/SettingsScreen.kt` | 默认 Agent、模型、权限、Gateway 和 About 分组 |
| `Views/TrajectoryView.swift` | `ui/ConversationScreen.kt` | 按需 KMP 轨迹投影、节点层级与状态 |
| `Core/Theme.swift`、`Components/Glass.swift` | `ui/DshTheme.kt`、`ui/DshProductApp.kt` | 颜色、圆角、玻璃层、排版和明暗层级 |
| `Components/HarnessAnimatedBackground.swift`、`Shaders/HarnessBackground.metal` | `ui/HarnessAnimatedBackground.kt` | AGSL 流体、技术网格、鲸鱼粒子、尾迹、闪烁和降级路径 |

Android 使用 iOS Asset Catalog 中的鲸鱼 SVG 原始 path，不使用近似图标。API 33 及以上运行 AGSL；API 24～32 保留网格、粒子鲸鱼和渐变降级。动画在应用不可见或系统关闭动画时停止。

## Android 液态玻璃

主页玻璃层参考 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的 Backdrop 架构，使用 `io.github.kyant0:backdrop:1.0.6`。选择 1.0.6 是因为它基于 Kotlin 2.3.10 / Compose 1.10.3，与本项目 Kotlin 2.3.20 和 BOM 解析出的 Compose 1.10.6 同代；仓库当前 2.0.1 使用 Kotlin 2.4.10 / Compose Multiplatform 1.12，不直接混入当前构建。

- `DshLiquidGlassHost` 只把主页动画背景录入共享 `LayerBackdrop`，玻璃控件本身不会被递归采样；
- Workspace 卡片、新建按钮、顶栏圆形按钮、搜索框和空状态使用统一 `dshLiquidGlass`；
- API 31+ 进行背景采样、vibrancy 和 blur，API 33+ 额外进行 lens、depth 和轻微 chromatic aberration；
- API 24～30 使用原半透明填充、边框和圆角降级，不调用不可用的 RenderEffect；
- 动态背景的 30fps 时钟同时生成 20fps 玻璃重录信号，只局部更新玻璃节点；进入后台或系统关闭动画时停止；
- 参数按控件类型分为 CONTROL、CARD、ACTION、FIELD、SUBTLE，避免所有表面使用同一强度；
- 第三方许可证记录见 `Docs/third-party-notices.md`。

## 自动化验证

在仓库根目录执行：

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :shared:allTests \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug \
  :androidApp:connectedDebugAndroidTest \
  --rerun-tasks
```

设备测试重点覆盖：

- Workspace 品牌标题、设置入口、新建会话等关键语义可被 Compose 测试和无障碍服务识别；
- 未连接时新建会话可进入输入器，不会错误发送 subscribe/unsubscribe 或弹出内部 `not-connected`；
- 阶段 12 产品组合测试继续覆盖 Runtime → Holder → Projection、History terminal、附件缩略图、Activity 重建与后台解码。

## 人工视觉检查

1. 冷启动后核对深色主页：白色系统栏图标、品牌头、完整鲸鱼轮廓、网格、流体光晕、玻璃卡片和底部渐变。
2. 连续观察背景 3～5 秒：鲸鱼粒子应缓慢漂移和闪烁，界面滚动与点击保持流畅；进入后台后动画停止，回前台恢复。
3. 点击 Workspace 卡片：选择面板应跟随系统明暗主题，文字与单选状态清晰可读。
4. 点击“新建会话”：浅色模式下状态栏图标变深；对话/轨迹分段、空状态、输入器、权限、Agent 与 Context 环可见。
5. 返回并打开设置：导航标题、分组卡片、Gateway 输入与默认配置行应与 iOS 信息层级一致。
6. 打开大字体、TalkBack 和“移除动画”分别复查：内容可滚动、按钮有名称、背景不进入无障碍树，关闭动画时只保留静态背景。
7. 连接真实 Gateway 后继续执行 `Docs/kmp-stage12-android-gateway-verification.md`；该结果属于 12.6.2，不由本页截图替代。

## 本轮设备证据

- Pixel 9 AVD，Android 15 / API 35；
- 液态玻璃与鲸鱼细节改造后最终强制门禁：KMP 89 项（共享层未改）、Android JVM 24 项、API 35 设备测试 8 项，均 0 失败；Android Lint 0 issue；
- Debug APK 为 14,379,644 bytes，`git diff --check` 通过；
- 主页、Workspace 选择面板、Conversation 和 Settings 均已从最终 Debug APK 实际截图并人工查看；
- 动态背景与液态玻璃由最终 APK 前台运行后连续截图验证；玻璃卡片内部裁剪区域的两帧哈希不同。Compose instrumentation 的实时协程时钟会冻结，未把该环境的缓存帧误记为产品失败；
- 鲸鱼点阵已按 Android `Path`/Compose 的 Y 轴向下坐标修正方向，并按 DeepSeek Harness 官网的 `24×18 SVG → 60×60` 等比抗锯齿采样、0.2 亮度阈值和完整内部路径实现；设备回归检查宽高比与腹部空洞，最终 APK 截图人工确认鲸鱼不再上下颠倒或横向挤压；
- Workspace 卡片、主按钮与圆形控制增强定向亮边、折射、背景鲜活度、内阴影和表面渐变，最终截图确认比上一版更通透、边界更清晰；
- 视觉检查中发现并修复了浅色系统下 Workspace/Gateway 面板“深色底配黑字”的可读性问题；
- 真实 Gateway 的全业务人工冒烟仍保持未完成状态。
