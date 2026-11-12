# P7-05d · 表格真实系统字号热更新

2026-11-12，Mate70Pro/API21 ARM64模拟器127.0.0.1:5557；隔离smoke包，真实系统设置“显示和亮度→字体大小和界面缩放”。标准2档→最大5档→标准2档，字体粗细/显示缩放始终1；未调用应用字体配置API或修改系统数据库。应用开启跟随系统，页面保留在栈内，pidof确认同进程。

## 修复及限定K/F结果

原产品在最大字号下文字放大、表头及部分行框却未放大，长单元格末行裁切。MeasureUtils.measureTextSize显式传24fp行高，App环境配置中的真实字号进入Observed Appearance，使表格自然列宽和行框在既有页面中重测。应用自定义字号仍显式vp，不持久化系统配置。API属性类型由本机API21声明核对；[官方文本测量属性](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkui/js-apis-measure.md#measureoptions)支持带单位字符串。修复依据本机实际复现，不仅依赖文档默认值推断。

同进程热更新和新进入预览的七个单元格宽高逐项相同，三行实际原生Row高度由168/252/168px增为206/450/206px，恢复标准后精确回到168/252/168px。Text表头内容区域82→120px，中文与西文长单元格166→364px，末行实际可见；超出屏宽仍通过横滚阅读。返回聊天草稿逐字保持。最大档及恢复原图和同帧布局已保存，row-height-proof.json区分实际Row行框与Text内容区域。

72项Node本轮实际执行通过；JVM/Kotlin Native因源码未变为UP-TO-DATE，最近实际全跑见E80。生产HAP构建成功。测试包95份ArkTS/C++/Native文件与工作树逐字一致，见source-manifest.json；没有字号注入或替代渲染器。ui.log/results.json记录真实F成功，system-font-sliders.json记录原值、最大、恢复与finally复核。smoke、专属夹具及新增映射在finally清理，正式包/日常网关/独立候选未操作。

## 原始失败

prior-slider-miss是自动化点中Slider无障碍外框的右侧留白，未改变原字号，不计产品断言；定位改为真实刻度标签中心。prior-fixed-height实际成功变为5档并复现产品裁切；同进程/新进入均有相同问题，断言如实失败。两轮finally均复核恢复2/1/1，未删除原始日志、截图或测量。

P7-05d限定表格热更新K/F完成；未证明所有页面/所有字号、读屏、宽屏V或真机。E84默认竖屏V单列保留。字号修复后的正式长流回归与新候选待本轮后续记录；目前已安装候选仍v8，不包含此修复。
