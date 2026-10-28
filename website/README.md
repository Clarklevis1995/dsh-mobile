# Whaleness 产品宣传页

入口是仓库根目录的 `index.html`。沿用现有 GitHub Pages 的 `main / (root)` 发布方式，推送后对应 `https://clarklevis1995.github.io/dsh-mobile/`。隐私政策仍位于 `privacy/index.html`。

页面使用原生 HTML、CSS 和 JavaScript，不依赖构建工具或第三方 CDN。支持手机布局、浅色与深色外观、功能页签、截图放大、键盘导航和减少动态效果设置。

导航、首屏和下载区均同时展示 iOS 公测与 Android 下载入口，两端按钮使用相同样式与尺寸。下载卡片在桌面端等宽，背景、边框、标题和按钮样式一致，移动端使用相同的卡片最小高度。

顶部导航与页脚的“加群获取支持”共用一个原生对话框，展示用户提供的微信群二维码。支持关闭按钮、Esc、点击遮罩关闭和保存图片，从手机菜单打开后关闭时焦点回到菜单按钮。窄于 1200 像素时导航收进菜单，避免新增入口挤压下载按钮。

二维码原图为 `assets/support-wechat-2026-10-28.png`，图片为 Whaleness 公测用户 2 群二维码，于 2026 年 10 月 28 日更新，注明 2026 年 11 月 4 日前有效。所有语言的项目 README 与产品页共用这张原图。更新群码时添加新的日期文件，并同步修改 `index.html` 中弹窗图片、保存链接与有效期描述，以及仓库根目录的 `README.md`、`README.en.md`、`README.zh-CN.md`，以及 `Docs/readme/` 中的所有其他语言 README 与本文中的图片路径和日期，再按现有 GitHub Pages 流程发布。

## 截图来源

用户提供的原始截图只生成网页优化资源，原文件不做修改。

| 网页资源 | 原始截图 | 用途 |
| --- | --- | --- |
| `home.webp` | `IMG_0914.PNG` | 首屏的工作区与会话主页 |
| `conversation.webp` | `IMG_0904.PNG` | 实时会话与任务执行 |
| `trajectory.webp` | `IMG_0897.PNG` | Agent 事件时间线 |
| `plugins.webp` | `IMG_0911.PNG` | 主机上的插件管理 |
| `tasks.webp` | `IMG_0916.PNG` | 定时任务详情 |
| `presets.webp` | `IMG_0907.PNG` | Agent 预设 |

截图统一生成 804 像素宽的 WebP，保留原始纵横比。鲸鱼标识复用 `Design/whaleness-orca-mark/` 中的 SVG。

首页、功能页签和 Agent 预设中的 iPhone 展示图由 Portrait Studio 的真实 3D 模型导出，位于 `assets/mockups/`，保留透明背景。首屏使用倾斜视角，功能区使用正面视角。点击功能截图时仍打开原始截图，便于阅读。透明 PNG 原稿与渲染参数保存在 `Design/whaleness-iphone-mockups/`，素材来源与许可记录见 [ATTRIBUTION.txt](assets/mockups/ATTRIBUTION.txt)。网页不设置独立的署名展示入口，旧的 `credits.html` 链接会返回首页。

连接指南另外选用 7 张桌面端截图，位于 `assets/setup/`：桌面首页、插件列表、添加插件窗口、插件启用状态、网关设置、连接与配对页面、连接方式菜单。桌面截图保留原始比例，宽图优化至 1600 像素宽；添加窗口和连接方式菜单保留原始尺寸。

图文指南分为 6 个步骤，支持上一步、下一步、直接选择步骤和截图放大。同一步的多张截图通过按钮切换。最后一步展示生成二维码的操作入口，用户需在自己的桌面端生成配对码。

连接指南按当前屏幕宽度为最长说明、截图切换按钮和最长图注预留空间，各步骤切换时展示区域与上下步按钮位置保持固定。内容使用 240 毫秒交叉淡化，连续点击会立即切换目标步骤并取消上一段动画；启用“减少动态效果”时直接切换。

## 本地预览

可以直接用浏览器打开根目录的 `index.html`，或在仓库根目录启动本机预览：

```sh
python3 -m http.server 8765 --bind 127.0.0.1
```

访问 `http://127.0.0.1:8765/`。页面下载入口分别使用 TestFlight 公测链接与 GitHub Releases 页面；桌面端与插件的安装说明见页面中的连接指南。
