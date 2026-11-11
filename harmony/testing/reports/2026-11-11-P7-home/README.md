# 未配对首页限定V与背景动态F

2026-11-11，冻结9e13dde3的Android1.7.12(33)独立参考与API21鸿蒙独立smoke。两端相同1316×2832/560dpi/376逻辑宽、100%字号、中文、未配对。Android临时系统动画比例0并冷启，鸿蒙通过产品减少动态后冷启，均采集时间零静态帧。原图和同帧布局已查看并保存；Android最后恢复1080×2424/420dpi及animator_duration_scale原null、输入法未改。

修复：按冻结数学在网格与鲸鱼间补56%→Navy遮罩、网格亮点，移除随机点亮度；原始SVG按72/100网格、4×4覆盖率生成1884/3576粒子，按亮度/边缘/漂移/交错/尾部波动/颜色绘制。系统避让区使用实际Window API及变化监听，映射完整窗口坐标后裁切绘制，控件继续遵守安全区。固定低分辨率流场仍在后台采样。首页空提示卡片上间距补10vp，主要文字白色、状态文字右对齐，主机条15vp图标/6vp状态点/34vp高度按源码修正。卡片改为ArkUI backgroundEffect明确半径/饱和度/亮度/蒙版，避免预设深色材质遮盖后面的光带。

限定V：9个共有标签在真实屏幕中的纵向中心误差都≤2vp，见geometry.json；不平移截图、不比较全屏相似度。白色主标题/新建/最近层级已按源码。背景轮廓和层次已改善，但低分辨率CPU流场/浮点精度、边缘抗锯齿近似、原生模糊没有Android镜头折射/色散、纯色系统栏、系统字形与阴影仍有差异；P2-02b/P7-07及全页V不关闭。首次截图虽然稍后显示首页，布局抓得太早只有时钟，因此丢弃其V结果，加入ready-text保护后重采集，保留失败布局。

最终生产Canvas临时只加计数，通过无网络独立测试页实测：11.8次提交/秒，平均JS提交5.878ms、最大23ms；页面停用/减少动态/真实Home后台持续增量均0。停止新后台任务且异步位图完成不在停用页继续提交，保留位图供恢复。指标是JS命令提交，不能当GPU帧时或真机功耗。首次测试计数器静态方法误用this导致ArkTS编译失败，改显式类引用后通过，产品绘制函数未替换。完整Native/HAP构建成功，Native为UP-TO-DATE，未覆盖正式包。

[打开原图并排对照](comparison.html)。API依据：[背景模糊官方说明](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/ts-universal-attributes-background/)、[Window避让区官方接口](https://developer.huawei.com/consumer/en/doc/harmonyos-references/arkts-apis-window-window/)、[避让区变化时序说明](https://developer.huawei.com/consumer/en/doc/harmonyos-guides/window-faqs/)，另已核对本机API21 SDK定义。不使用API23新增环境数据类型。
