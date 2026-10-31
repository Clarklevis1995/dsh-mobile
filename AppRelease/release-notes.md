## 功能更新

- 双端新增“全部会话”视图，支持跨工作区浏览、按工作区分组及展开更多会话；新建会话时提示先选择具体工作区。
- 双端新增 Agent 等待回答或选择时的后台通知，切入后台后补发仍待处理的回答与审批提醒；修复 Android 部分任务结束通知遗漏的问题。
- 优化会话列表加载与滚动流畅度；iOS 在列表滚动时暂停背景动画，停止滚动后平滑续播。
- 调整双端模型与推理等级菜单的分组和间距；iOS 保留原生菜单展开效果，将推理等级置于顶部，并固定选项顺序。
- 修复 iOS 点击上下文用量圆环后显示为底部面板，以及浮层箭头与面板背景颜色不一致的问题。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.11.1` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
```
