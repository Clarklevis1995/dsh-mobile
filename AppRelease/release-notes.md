## 功能更新

- Android 支持一次选择并上传多张图片，已发送的图片也可以保存。
- iOS 和 Android 会话图片支持全屏预览和左右滑动，长按图片可打开保存菜单。
- 修复生成图片只有下载链接时缺少预览的问题，正文已展示的同一张图片只显示一次。
- 修复 iOS 重启后外观设置恢复为跟随系统的问题，保留用户所选的深色、浅色或跟随系统。
- 改善长回复的流式 Markdown 显示和滚动体验，修复轨迹中模型耗时的计时起点。
- 改善 iOS Apple Music 卡片的播放反馈，补齐双端文件改动页面的翻译，并兼容未记录 Agent 预设的旧会话。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.12.2` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@0.12.2
```
