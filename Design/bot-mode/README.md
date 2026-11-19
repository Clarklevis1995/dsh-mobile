# Bot 模式设计资源

`ds-bot-whale.svg` 提取自 DS Bot 的「返回 DS Bot」入口，使用原始 `MAIN_LOOK`（`whale` / `deepseek`）矢量定义，按抽屉设计将主色调整为黑色 `#000000`，保留原始轮廓、透明眼睛和无背景；图标尺寸及位置保持不变。

上游仓库：https://github.com/Fei2411/DS-Bot
固定版本：`a66cc737cfb6c3d8a2597e748cded6cab1206dd3`
来源：`ds-bot/src/client/characters.js`、`inks.js`，入口定义见 `sidebar.js` 的 `BotReturn`。

保留上游图形及品牌归属；上游仓库采用 Apache-2.0，原始作者版权说明与第三方声明见上游 LICENSE 和 NOTICE。


## Figma 矢量资源

`group-avatar.svg`、`conversation.svg`、`mode-swap.svg` 来自已批准的 [Figma 文件](https://www.figma.com/design/ZUTrLysFm9CZlTW51W7pj5)，节点分别为 `33:1465`、`32:1469`、`32:1473`。保留矢量路径与源尺寸，分别导入 iOS Assets 和 Android VectorDrawable；没有把完整页面截图作为界面资源。

## 布局验收快照

`preview-fixture.json`、`preview-fixture-long.json`、`preview-fixture-empty.json` 只供 Debug/设备测试。iOS 验收入口使用隔离偏好和凭据；Android 测试使用内存主机配置，不连接真实主机。

运行 `python3 scripts/sync-bot-design-fixture.py` 将这些可读输入同步到 iOS DEBUG 数据及 Android androidTest 资源；加 `--check` 仅检查同步状态。生产数据始终来自共享 Bot Store 与移动网关。
