# 本地数学排版补丁

上游：https://github.com/mgriebling/SwiftMath ，1.7.3，提交 fa8244ed032f4a1ade4cb0571bf87d2f1a9fd2d7。保留 MIT 许可证及字体资源。

补充 AMS `\boxed{...}` 数学节点，在排版时计入边框和内边距，绘制真实矩形；继承当前数学字号、颜色及样式，支持分式、上下标和嵌套方框。普通数学节点保持上游实现。

修改文件：MTMathList.swift、MTMathListBuilder.swift、MTTypesetter.swift、MTMathListDisplay.swift。应用层 MarkdownMathTests 包含修改前复现、截图及原有数学渲染回归。
