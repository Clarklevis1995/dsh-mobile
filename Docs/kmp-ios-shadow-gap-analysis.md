# iOS / KMP 影子迁移能力差距

> 基线：阶段 7 检查点 `454be78`  
> 盘点日期：2026-08-26  
> 范围：阶段 8 的生产 Facade、错误边界、只读影子验证和对等 fixture

## 当前结论

KMP 已包含 Gateway DTO、wire decoder、SessionList/Question/SessionControl/History Reducer、Conversation/Trajectory 投影；但 `SharedMobileStore` 仍是 Android Fixture 验证入口，并不是可以直接替换 iOS `GatewayFrameRouter/AppStore` 的生产边界。

现有 iOS 路由覆盖 30 类响应，KMP store 只主动处理 `sessions`、`event`、`history` 和三类 Question frame。KMP 尚未向 Swift 暴露稳定的 route/effect、完整领域 fingerprint、错误码或串行调用约束。因此阶段 8 不能直接切换 iOS 状态来源，必须先建立只读影子层。

## 能力矩阵

| 能力 | Swift 当前实现 | KMP 当前实现 | 阶段 8 处理 |
|---|---|---|---|
| 缺省 `kind:event` 修复 | `GatewayWireDecoder` | 已有 | 保持对等 fixture |
| Gateway DTO | 完整 | 字段基本完整 | 用全量 route fixture 验证 |
| Connection 路由 | paired/hello/pong/subscribed | store 未路由 | 生产 route + 显式 effect |
| Content 路由 | sent/event/workspaces/sessions/history/attachment/search/host | 仅 sessions/event/history | 补齐全部 route |
| Control 路由 | presets/defaults/models/permission/context/stats | Reducer 已有，store 未接入 | route 输出领域 action 摘要与 finish effect |
| Workspace 路由 | directories/create/workspace-create | 未接入 | 作为平台 effect 输出 |
| Human Question | 完整 | 基础状态转换已有 | 补齐 malformed、response 默认 action 和 effect 摘要 |
| Error 路由 | 按 requestType 清理平台状态 | 仅记录 decode error | 输出结构化 failure effect，不执行平台 I/O |
| SessionList Reducer | 产品使用中 | 已迁移 | 影子 fingerprint/fixture 比较，UI 暂不切换 |
| Question Reducer | 产品使用中 | 已迁移 | 影子 fingerprint/fixture 比较，UI 暂不切换 |
| SessionControl Reducer | 产品使用中 | 已迁移 | 通过 route/action fixture 验证，不切换写路径 |
| History Reducer | 产品使用中 | 已迁移 | 通过 reducer fixture 验证 cursor/result fingerprint |
| Conversation/Trajectory | Swift 产品使用 | KMP 纯实现已有 | 增量桥接留到阶段 10；阶段 8 只做 fixture parity |
| 平台 effect | `AppStore/GatewayClient` 执行 | 无显式契约 | KMP 只输出 effect descriptor，Swift 不执行影子 effect |
| Swift 错误边界 | 直接调用 Kotlin API | `decodeFrameKind` 可能抛出 Kotlin 异常 | 所有影子 API 返回 result/error，不抛出 |
| 线程约束 | `AppStore` 为 `@MainActor` | 文档约定，无 Swift 封装 | `@MainActor KMPShadowValidator` 串行入口 |
| 自动差异发现 | 无 | 无 | DEBUG 下逐 frame 比较 Swift/KMP route fingerprint |

## 阶段 8 的公开边界

```text
Gateway frame JSON + routing context JSON
                    │
                    ▼
         SharedShadowFacade.routeFrame
                    │
                    ├── route fingerprint JSON
                    ├── platform effect descriptors
                    └── explicit error code/message

Swift GatewayFrameRouter ── route fingerprint ──┐
                                                ├── KMPShadowValidator 比较
KMP SharedShadowFacade ──── route fingerprint ──┘
```

- 影子层只观察，不执行 effect、不写 UserDefaults、不调用 GatewayClient、不发布 UI。
- 每个 frame 仍只由现有 Swift `AppStore` 执行一次产品行为。
- route fingerprint 只包含协议语义字段，不比较本地化 notice 文案或 UIKit/SwiftUI 展示状态。
- Conversation 的每 token 增量性能边界在阶段 10 处理，阶段 8 不把完整消息数组跨桥接层复制到产品路径。

## 允许差异

- 本地化标题、错误文案和 notice 文案；KMP 只比较语义错误码。
- Swift `Date.now` 与 KMP 无时钟 fallback 产生的临时时间；远端时间戳必须一致。
- 图片缓存命中、后台任务标识、SwiftUI/UIKit 展示状态等平台结果。

## 阶段 8 完成门槛

1. KMP route 覆盖 Swift Router 的全部已知 frame kind。
2. 所有 KMP Swift 入口均返回显式 result/error，坏 JSON 不导致进程终止。
3. `AppStore` 在 DEBUG 下逐 frame 执行只读 route 比较，Release 不增加影子开销。
4. Swift/KMP 使用同一组 route fixture，已知 frame 无差异，未知和 malformed frame 有确定结果。
5. iOS、KMP、Android 自动化与构建全部通过；真实 iOS 连接流程作为人工核验项单独列出。
