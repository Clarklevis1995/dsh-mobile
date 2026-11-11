# P2 组件预览与视觉核对

日期：2026-10-30。参考为冻结的 Android 源码与隔离参考包截图；鸿蒙为 API 21、Mate70Pro ARM64 模拟器的真实 ArkUI 输出。Android 参考视口约 411dp，鸿蒙约 374vp，不能把两张全屏缩放后的像素相似度当作验收。

| 对象 | 已核对的实现／证据 | 尚待关闭的差异 |
| --- | --- | --- |
| 色彩 | `DshTheme.kt` 通用背景／表面／主色与 `SettingsScreen.kt` 专用背景分别映射；明暗组件预览可复查 | 尚未覆盖所有业务页状态 |
| 字体与基础控件 | 100%／150% 下标题、正文、卡片、禁用、加载、错误栏、输入及菜单锚点实际显示；错误内容自然换行，无关键文字裁切 | HarmonySans 与 Android 系统字体度量不同；完整页面数值校核未结束 |
| 图标 | 原始路径导出；实心 SVG 使用 fillColor，描边 SVG 使用 SRC_IN，修正白底不可见与错误填充折线问题 | 新增图标需继续按资源类型校核 |
| 品牌首页 | 授权字标／鲸鱼路径、42vp 网格、32fp 标题、14/16＋12/14 工作区文字、14×15vp 内边距、52vp 高／18vp 圆角主按钮，26vp 圆形加号与 17fp／600 字重已映射 | Canvas 与 AGSL 的局部光效、玻璃材质仍有差异；动态已测活动约11.05次/秒，停用和减少动态增量0，前后台切换增量1；JS提交平均1.40ms，仍不代表GPU或真机指标 |
| 外观与语言 | 原生锚定菜单已替代独立选择页；菜单勾选与原生主题跟随应用偏好，冷启动后保留 | 当前源产品字号页也是独立页面；已补聊天式预览、5% 步进与恢复默认，完整 V 仍待关闭 |
| 导航 | 首页、聊天、设置、主机／认证／编辑、工作区／目录、侧栏与待处理已拆分，返回与状态基础回归通过 | 手机覆盖抽屉已通过 F，底层页面、遮罩与返回关闭可用；宽屏和全状态 V 尚未关闭 |
| 工作区与会话 | 实际 Host 目录／新建工作区、筛选、搜索、重命名、归档取消及确认均有 G；空分组按服务端 sessionIds 显示 | 最近／空／离线／错误／加载的完整同逻辑视口截图矩阵未齐 |

组件预览源码：[ComponentGallery.ets](../../compatibility/ComponentGallery.ets)。该独立包不包含网络请求，全部内容为明确标注的诊断样例。四组截图见 [P2-components](../../screenshots/P2-components/)。菜单、主机与错误状态见 [P2](../../screenshots/P2/)，真实管理见 [P2-isolated](../../screenshots/P2-isolated/)。重命名弹层、归档确认／取消还通过了真实 ArkUI 操作，但不以此冒充完整弹层 V 矩阵。

本报告只证明已经列出的组件与业务接线。P0-04、P2 父项的完整视觉要求与 P7 差异关闭仍按规划书未勾选项执行，不宣称完整 UI 复刻已经完成。

原始测量见 [brand-dynamics.json](brand-dynamics.json)，抽屉见 [drawer-results.json](drawer-results.json)。Android 参考已替换为准确指定设备的新独立包首次安装截图，详见 [capture-scope.json](../../baseline/android/capture-scope.json)。

补充：输入弹层与可缩放确认框的四组检查见 [modal-review.md](modal-review.md)。当前 Android 字号页实际也是独立页面，已按源布局补聊天式预览、5% 步进、恢复默认和灰色刻度滑轨。官方默认不跟随系统字体，已显式配置 followSystem；见 [官方配置](https://github.com/openharmony/docs/blob/master/en/application-dev/quick-start/app-configuration-file.md#configuration)。物理系统字号变更与宽屏组合尚未作为已验证结论。

系统字体机制探针：只在无网络 Gallery 内使用应用级 setFontSizeScale(1.5)，同一标题像素高度比为1.5102，确认 followSystem 配置下 fp 路径会放大。该探针不修改设备系统字号，也不证明物理设置变更／重启组合，见 [探针结果](system-font-application-probe.json)。显式覆盖层下仍发现一次菜单点击丢失，因此没有把返回问题根因认定为 SideBarContainer；UI测试现仅对无副作用导航及同值偏好选择做最多一次重读重试，所有重试记录保留。
