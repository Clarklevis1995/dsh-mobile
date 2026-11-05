## 功能更新

- 双端支持在会话中预览工具生成的图片；iOS 图片加载失败后可重试，改善切换会话、重连后的附件恢复。
- iOS 进入会话时自动加载最近两页并统一展示，修复运行中会话首屏内容不完整、补页时列表连续跳动的问题。
- 修复 iOS 输入框和任务面板高度变化时遮挡底部消息的问题，改善设置子页导航稳定性及权限名称显示。
- Android 工作区新增图片、SVG 和视频预览，支持保存图片。
- Android 新增「UI 降级」开关，可停止主页背景动画、降低毛玻璃效果，并优化主页及设置页的显示。
- 双端最近会话列表支持展开与收起，方便查看更多历史会话。
- 修复 Android 选择 Markdown 文本后退出选区时可能导致的崩溃。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.11.3` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
```
