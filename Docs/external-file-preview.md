# 工作区外文件预览与下载

更新日期：2026-11-12。

Android 和 iOS 均支持打开会话交付卡片、正文文件链接中指向工作区外的普通文件。支持 POSIX 绝对路径、Windows 盘符路径与 UNC 路径；中文、空格保留，Windows 下载路径统一为 `/` 分隔。远端路径只交给网关解析，下载后的内容写入手机临时文件并校验 SHA-256，再用于预览和另存为。

需要同时更新移动 App 和 `dsh-plugin-mobile-gateway`。本次已修改兄弟仓库的网关源码；未发布新安装包，也未替换正在运行的网关插件。

## 网关读取方式

工作区内的文件保持原有读取方式和预览缓存。工作区外的文件通过 DSH 的 `workspaceFiles.stat`、`workspaceFiles.readBytes` 读取，传入 `workspaceFileScopeId: sessionId`，由 Host 根据会话判断读取权限。DSH 拒绝时不回退到 Node 文件系统读取。

外部文件解析结果返回 Host 的绝对路径。Host 的 `version` 是不透明值，不能当作修改时间；外部文件不写入基于 `modifiedAt` 的现有预览缓存。每个分块读取都检查版本、路径与文件大小，文件变化则中止，避免混合不同版本的内容。

`file-list` 仍限定在会话工作区；路径穿越、空路径、NUL、目录与非普通文件继续拒绝。分块偏移、大小上限、连接所属传输、取消、过期和整文件摘要校验沿用原有机制。子会话继续使用既有工作区会话绑定。

文件不存在、读取被拒绝、文件变化和超出大小限制均显示对应提示。预览中的文件错误留在预览面板内。

## 验证结果

- `:shared:iosSimulatorArm64Test`：627 项通过，其中新增 7 项外部文件路径、下载完整性、子会话、目录边界与错误码测试。
- `:androidApp:testDebugUnitTest`：299 项通过，Android 最新修改已重新编译并回归。
- iOS `WorkspaceFilePreviewPresentationTests` 与 `LocalizationCatalogTests`：29 项通过，包含 Windows/POSIX 外部文件落地预览和权限拒绝测试。
- 网关 `external-files.test.mjs` 与 `file-upload.test.mjs`：17 项通过；Host adapter 回归通过；原有 WebSocket 网关集成 148 项检查通过。
- 本地化守卫及两个仓库的 `git diff --check` 通过。

验证使用本机 iOS 模拟器、共享层自动化测试及网关测试替身，尚未在 Windows 真机网关和 Android/iOS 实机上联调。iOS XCTest 命令临时指定 `IPHONEOS_DEPLOYMENT_TARGET=17.0` 以匹配现有测试代码的系统 API 可用性，未修改产品部署目标。
