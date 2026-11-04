# 日志导出与分享图标

图标来自 Figma Simple Design System 的 Download 和 Share，采用同一套圆角线条。

- Figma 预览：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=27-193
- Download 组件：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=27-189
- Share 组件：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=27-191
- 来源：https://github.com/figma/sds
- Feather 图标与许可：https://github.com/feathericons/feather

## 规格

画布为 24×24，透明背景，2 单位线宽，圆形端点和连接。
下载图形做了光学校正，使两枚图标的可见高度一致。
Figma 组件使用共享颜色变量 color/icon/primary；SVG 导出色为 #1E1E1E。

## 双端使用

以本目录 SVG 为统一源文件：Android 转为 VectorDrawable，iOS 导入矢量资源。
保留 viewBox、路径、线宽与居中方式，统一以 24dp / 24pt 显示，按钮区域为 44dp / 44pt。
深色模式可使用模板着色；重新生成资源时也应使用相同路径。
随分发资源保留 LICENSE 中的版权和许可声明。

## 已接入的客户端资源

iOS 使用 LogDownload / LogShare 模板矢量资源；Android 使用 ic_log_download / ic_log_share VectorDrawable。
两端图标均以 24pt / 24dp 显示，按钮区域为 44pt / 44dp。许可声明已随双端资源打包。

修改源 SVG 后，执行 `python3 scripts/sync_log_export_icons.py` 同步；
执行 `python3 scripts/sync_log_export_icons.py --check` 可检查两端路径、画布和线宽是否一致。
