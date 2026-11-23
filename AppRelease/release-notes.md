## 功能更新

- 新增 Bot 模式，集中查看 Chief、进行中的工作、完成记录和定时任务，支持 Bot 私聊与群聊。
- 新增 Bot 管理与创建入口，可设置名称、头像、外观和模型；优化手机与平板的首页、搜索和聊天布局。
- 会话输入框新增相机拍照入口，优化 Bot 图片、文件回复和流式消息的展示体验。
- 优化主机连接方式的选择与记忆，重新配对时保留其他连接方式，改善多主机切换体验。
- iOS 支持通过携带网关身份的会话链接打开对应主机的会话。
- 改善双端主题背景、菜单、日期时间选择和定时任务面板，优化键盘避让、文字选择及大字号显示。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.12.3` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@0.12.3
```
