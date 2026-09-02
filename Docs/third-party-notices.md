# 第三方软件声明

## DeepSeek Harness WebUI 图标

- 项目：DeepSeek Harness
- 作者：DeepSeek
- 仓库：https://github.com/deepseek-ai/deepseek-harness
- 来源版本：`4e84901e6471b79ec0338099867ebb4606d12bb5`
- 许可证：MIT License
- 许可证原文：https://github.com/deepseek-ai/deepseek-harness/blob/master/LICENSE

本项目将 WebUI 的 `IconThinkOutline14`、`IconSearchOutline16`、
`IconBrowseOutline16`、`IconApiOutline14` 和
`IconContextInjectionOutline16` SVG 路径转换为 Android VectorDrawable
与 iOS Template Image，用于原生会话过程行。版权归 DeepSeek 所有，转换后的
资源继续遵循原项目 MIT License。

```text
MIT License

Copyright (c) 2026 DeepSeek

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Backdrop / AndroidLiquidGlass

- 项目：AndroidLiquidGlass（发布产物名 `Backdrop`）
- 作者：Kyant
- 仓库：https://github.com/Kyant0/AndroidLiquidGlass
- 使用版本：`io.github.kyant0:backdrop:1.0.6`
- 许可证：Apache License 2.0
- 许可证原文：https://www.apache.org/licenses/LICENSE-2.0

本项目通过 Maven 依赖链接该库，用于 Android Compose 背景采样、模糊、色彩增强和折射渲染；未复制其示例组件源码。DeepSeek Harness 自定义了自己的玻璃组件、参数、低版本降级和无障碍行为。

## Markwon

- 项目：Markwon
- 作者：Dimitry Ivanov（noties）及贡献者
- 仓库：https://github.com/noties/Markwon
- 使用版本：`io.noties.markwon:4.6.2`
- 许可证：Apache License 2.0
- 许可证原文：https://www.apache.org/licenses/LICENSE-2.0

本项目通过 Maven 依赖链接 Markwon Core、GFM Tables、Strikethrough、Task List 和 HTML 扩展，用于 Android 会话正文的 Markdown 渲染。未启用远程图片加载扩展，图片仍只通过应用既有的 Gateway 附件校验与缓存链路进入界面。
