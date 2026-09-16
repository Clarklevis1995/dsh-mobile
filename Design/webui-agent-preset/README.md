# WebUI 会话模式图标

`agent-preset-outline-16.svg` 提取自本机已安装的 `@deepseek-ai/dsh-web-frontend@0.1.5-rc.2`，对应 `IconAgentPresetOutline16`。模式选择器 `AgentPresetSeat` 引用的就是该组件。

来源：`dist/assets/index-BKQ_L1z6.js` 中导出为 `IconAgentPresetOutline16` 的组件 `lp`。

原始 SVG 保留 16 × 16 viewBox、四条路径、三个圆形遮罩及全部路径坐标。许可证见同目录 `LICENSE`。

移动端使用同一套几何数据：

- Android：`androidApp/src/main/res/drawable/ic_agent_mode.xml`。
- iOS：`DeepSeekHarnessMobile/Resources/Assets.xcassets/DshAgentPreset.imageset/dsh-agent-preset.svg`。

为兼容原生矢量资源，将“白色矩形减去三个黑色圆”的遮罩等价转换为 even-odd 裁剪路径。只裁剪环形连接线，三个节点轮廓仍按原始路径绘制；两端均以 16pt/dp 显示并使用界面前景色。
