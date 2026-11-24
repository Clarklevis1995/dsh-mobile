## 功能更新

- 工作区会话新增置顶与取消置顶，置顶状态与主机同步，并在各工作区分组内优先显示。
- Bot 首页新增可滚动的新建菜单，支持选择 Bot 打开对话或创建群聊；首位选中成员担任管理员，可填写首条消息。
- 修复隐藏 Bot 后相关群聊消失的问题，查找 Bot 和选择群成员时仍可访问隐藏成员。
- Android Bot 模式补齐页面进入和返回动效，支持逐层返回，并保留表单草稿与列表滚动位置。
- iOS Bot 首页滚动时暂停背景动效，优化平板聊天气泡宽度。
- 修复双端新建群聊、查找 Bot 页面点击空白处无法收起键盘的问题，调整新建按钮及已选成员计数样式。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.12.4` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@0.12.4
```
