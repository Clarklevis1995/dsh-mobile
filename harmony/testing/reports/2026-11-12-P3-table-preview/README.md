# P3-04d · 表格预览视觉与旋转验收

2026-11-12，API21 ARM64 Mate 70 Pro模拟器，127.0.0.1:5557，1316×2832/560dpi、376vp、默认字号。只操作隔离smoke包。Android冻结参考9e13dde3/1.7.12(33)，同内容、同物理尺寸与376逻辑宽；截图和同帧布局见reference，源码与资源哈希见reference-source-hashes.json。最终生产文件哈希见source-hashes.json。

## 已通过的限定K/F/V

- 原生预览采用冻结页头、表格配色、17fp文字和96～260vp自然列宽；窄表比例铺满，长表仍使用惰性List。图标直接转换仓库中相同Android矢量，44vp触区和读屏名称保留。
- 浅深竖屏64项位置/尺寸：最大误差0.857vp；10项实际字形核心高度：最大差0.286fp；18项表头、隔行、背景、按钮、图标及边线配色全部通过。扣除关闭按钮中心测得的系统顶部46px差异，不使用全屏相似度或声明字号代替测量。geometry-comparison.json及比较脚本可复核。
- 横屏边缘手势返回、横竖两次旋转关闭、旋转后立即Back、深色横屏关闭均恢复原方向与聊天草稿。orientation队列退出后等待在途工作再恢复；rotation保存原图、布局与结果。
- 最终版表头/数据三列边界一致、实际横滚同步、复制TSV逐字一致；DocumentViewPicker真实取消/Download保存通过。89字节UTF-8 BOM/CRLF中文CSV按原字节ZIP归档，取消/保存与清理后草稿保持，仅删除本轮UUID文件。见operations。
- 最终160行表格实际滚到159后返回通过，见long-reading。完整6项富文本F包含代码原文复制/实际高亮像素/展开/横滚、跨段全选复制、流式终帧及轨迹详情返回，见richtext。
- scripts/build-harmony.sh test通过：72项Node本轮实际执行，163项JVM因Kotlin未变为UP-TO-DATE，最近实际全跑见E80。Native compile/link/publish也UP-TO-DATE，真实HAP BUILD SUCCESSFUL；core.log与production-build.log保留。
- 共享表格/文字变更后的正式长流F具备验收资格，原8门禁全部通过：初始PSS 180.56MiB，3秒201.44MiB（增20.88MiB），30秒177.13MiB，1秒采样峰值253.39MiB。两轮55274个UTF16单元、FNV3682621544与源样本一致，读位/草稿/前后台通过。见long-stream；未改GC、Worker或冻结阈值。

## 修复与失败保留

异步解析后的表头高度需可观察状态重算；Builder必须尾随传入，避免工具回调this绑定到页头。CJK固定24fp行框、纯西文自然行框分别对照实际字形；onlyBetweenLines只对自然行框启用，其他Markdown原行距保留。此前高度裁切、回调崩溃、基类rotate命名冲突、行数旧断言及两种不合格字框样本均以prior-*保存，没有拿旧图充作最终版。

## 环境与范围

Android尺寸/输入法恢复，专属视觉Host/鸿蒙手动夹具/映射已定向清理，正式Android/鸿蒙包与用户网关未操作，见cleanup记录。F脚本自己的临时包/进程/映射在finally清理。独立候选仍需更新后另记版本。

P3-04d限定预览页默认竖屏K/F/V完成。横屏只证明旋转和恢复F，Android cutout/system bar与鸿蒙可用视口不同，不声明横屏全V。聊天内表格工具栏、全页视觉、系统动态字号、宽屏、读屏、完整GPU帧时间/全局预算仍待对应父项验收；P3-04和全P7不勾选。无真机，不扩大为D或用户签收。
