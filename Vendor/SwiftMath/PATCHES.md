# 本地数学排版补丁

上游：https://github.com/mgriebling/SwiftMath ，1.7.3，提交 fa8244ed032f4a1ade4cb0571bf87d2f1a9fd2d7。保留 MIT 许可证及字体资源。

补充 AMS `\boxed{...}` 数学节点，在排版时计入边框和内边距，绘制真实矩形；继承当前数学字号、颜色及样式，支持分式、上下标和嵌套方框。普通数学节点保持上游实现。

修改文件：MTMathList.swift、MTMathListBuilder.swift、MTTypesetter.swift、MTMathListDisplay.swift。应用层 MarkdownMathTests 包含修改前复现、截图及原有数学渲染回归。

## 2026-11-12 数学命令兼容

`MTMathListBuilder.swift` 的两条命令解析入口共同使用 `extendedMathCommand`：

- `dfrac/tfrac`、`dbinom/tbinom`：通过局部数学样式保留显式 display/text 排版要求。
- `operatorname` / `operatorname*`：使用大型运算符节点，保留运算符间距与 limits 行为。
- `iint/iiint`：组合积分节点，保留脚标与积分符号。
- `tag/tag*`：保留编号文本，采用直立字形并在公式末尾输出；不因为编号命令让整个公式退回源码。

共享回归用例位于仓库 `Tests/Fixtures/latex-math.json`。
