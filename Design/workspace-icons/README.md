# 工作区菜单图标

Figma 预览：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=40-214

| 图标 | 来源 | Android 资源 |
| --- | --- | --- |
| 全部会话 | 本项目自绘叠层矩形 | ic_workspace_all |
| 未分组 | Figma Simple Design System / Inbox | ic_workspace_inbox |
| 工作区 | Apple SF Symbols / folder，SF Pro Regular 导出 | ic_folder_outline（ic_workspace_folder 为别名） |
| 已选中 | Google Material Symbols Rounded / check_circle，Fill=1 | ic_workspace_selected |
| 添加工作区 | Figma Simple Design System / Plus | ic_workspace_add |

Android 图标区域统一为 24×24；文件夹使用与 iOS 一致的扁形双层轮廓，保留原 SVG 的 27×22 比例，在方形区域内垂直居中。
`folder.svg` 是从 Figma 的 SF Pro Regular `folder` 系统符号导出的轮廓。原先 Simple Design System 的 Folder 已替换，上面的 Figma 菜单预览仍是旧版。
文件夹在首页、会话、工作区菜单、文件列表、目录选择和存储页面共用同一个矢量路径；运行时沿用各处的前景色进行模板着色。

SVG 是 Android VectorDrawable 的路径来源。Inbox 和 Plus 的 Simple Design System 资源使用 MIT 许可，
Google Material Symbols 使用 Apache 2.0 许可；相关声明已随 Android assets 分发。
