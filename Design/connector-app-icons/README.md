# 连接器应用图标

六款苹果系统应用风格图标：日历、提醒事项、位置、照片、音乐、闹钟。

Figma 可编辑组件与 40 点预览：[查看设计](https://www.figma.com/design/0qYyEvoG07J77OKPPnseTp?node-id=11-111)。

- 使用 Figma AI 矢量生成（消耗 Figma AI 点数），不是位图描摹或苹果官方图标文件。
- 日历的星期和日期由 SF Pro 排版补齐，导出 SVG 时文字转为路径。日期是静态图形。
- 闹钟铃耳经手工修正，左右严格镜像，分别旋转 -38° / +38°。
- SVG 母版保留线性渐变、透明度和地图裁剪。
- Android 用 `python3 Design/connector-app-icons/export_android.py` 转为原生 VectorDrawable；列表显示为 38dp，不做单色着色，无位图放大模糊。
- 本轮替换 Android 连接器列表的六个图标。iOS 原有系统应用素材保持原样。

## 生成提示词

共同要求：Apple native app visual language, 256×256 artboard, rounded square tile, subtle ivory-white gradient, flat frontal view, precise large simple vector shapes, legible at 40px, no purple Material Design background, no thick outline, no mockup, no raster.

- 日历：Calendar app icon. White ivory squircle. Tiny red uppercase FRI at upper center, large elegant black number 17 centered below. Optical typography converted into vector paths, no extra decoration.
- 提醒事项：Reminders app icon. White ivory squircle. Three neatly aligned rows, hollow colored circular bullets on the left in blue, orange, red, three thin light gray horizontal lines on the right. Crisp, spacious, familiar Apple Reminders visual grammar.
- 位置：Maps / location app icon. Fullbleed rounded square stylized city map with soft green park blocks, pale blue water and crisp white intersecting roads, a small blue circle containing a clear white navigation arrow at center. Road geometry restrained and map label free.
- 照片：Photos app icon. White ivory squircle. An elegant perfectly symmetric eight petal translucent rainbow flower: each petal elongated rounded oval, center overlaps, red orange yellow green cyan blue purple pink, crisp clean Apple Photos inspired mark, generous padding.
- 音乐：Music app icon. White ivory squircle. A large bold black double eighth musical note at center, connected rectangular beam and two round note heads, gentle polished charcoal gradient, enough breathing room. Modern Apple system app icon simplicity.
- 闹钟：Alarm clock app icon. White ivory squircle. Large vivid orange filled alarm clock silhouette with two small separated orange ringing bells, two short feet. White clock dial ring and white hands showing 10:10. Very legible, optically centered.
