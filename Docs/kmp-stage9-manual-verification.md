# 阶段 9 iOS 人工核验清单

## 测试准备

- 使用 Debug 配置启动 iOS App，连接真实 Gateway，并保持 Xcode Console 可见。
- 至少准备两个会话 A、B；最好包含一个旧安装已有会话、一个可产生 Human Question 的任务。
- 本轮只核验阶段 9.1～9.4；通过前不执行阶段 9.5，也不删除回滚能力。

## 1. 会话列表与旧数据

- [ ] 升级安装后，旧会话、归档状态和上次选择仍存在，没有丢失或重复。
- [ ] 新建或刷新会话后，列表排序正确；运行中、已归档和未读标记与实际状态一致。
- [ ] 选择会话 A，再切到 B，标题、运行状态和内容均属于当前会话。
- [ ] 归档/取消归档一个会话，重启 App 后状态仍正确；当前选择不会指向不可见的已归档会话。

## 2. Human Question

- [ ] 收到问题后，题目、选项、单选/多选限制和待回答状态正确。
- [ ] 提交合法答案后只发送一次，界面进入已完成状态，不会重复弹出。
- [ ] 再触发一个问题并取消，取消只发送一次且状态正确。
- [ ] 尝试空答案或不符合规则的答案，客户端拒绝提交且已有问题状态不被破坏。
- [ ] Gateway 拒绝答案或取消时显示失败，并允许按产品既有行为恢复或重试。
- [ ] 重放已 resolved 的问题、重复响应或对非 pending 问题操作时，不重复发送、不复活旧问题、不崩溃。

## 3. 模型、权限、Context、Stats 与默认配置

- [ ] 打开会话控制区，模型目录、当前模型和 reasoning effort 正确；切换模型后刷新仍保持服务端结果。
- [ ] 权限选项与当前权限正确；切换 `read-only`、`workspace-write` 等支持值后状态及时更新，不出现不支持值。
- [ ] 连续两次进入同一会话并打开控制区，即使 Gateway 的 `models` / `permission-options` 响应省略 `sessionId`，模型和权限仍能完成加载且不会超时。
- [ ] Context Usage 与 Stats 能加载并随新消息刷新，token、turn、step 等数值合理且不会回退到其他会话。
- [ ] Agent Presets 可加载；可用预设可设为默认，损坏或未知预设不能设为默认。
- [ ] 修改默认模型、默认权限或默认 Agent Preset 后重新进入设置/重启 App，服务端确认值仍正确。
- [ ] 快速在 A ↔ B 之间切换并连续打开控制区，A 的模型、权限、Context、Stats 不得显示或写入 B，反之亦然。

## 4. 断线与重连

- [ ] 在控制数据加载中断开 Gateway，App 不崩溃、不永久转圈，也不执行重复请求。
- [ ] 恢复连接后重新进入 A、B，模型、权限、Context、Stats 和默认配置可正常刷新。
- [ ] 断线前请求的迟到响应不得覆盖重连后的会话或新选择；Human Question 也不得跨连接代际重复提交或复活。

## 通过标准

- 上述项目全部通过；UI、旧数据和 Gateway 请求语义与迁移前一致。
- 没有跨会话污染、重复网络 effect、永久 loading、旧问题复活、崩溃或数据丢失。
- Console 不出现下列关键词；普通网络抖动只有在导致功能无法恢复时才算失败：
  - `KMP SessionControl 初始化失败`
  - `KMP SessionControl 运行期结果失效`
  - `无法解码 KMP SessionControl`
  - `KMP 影子差异`
  - Kotlin/Native 未捕获异常或进程崩溃

人工核验通过后，在迁移计划的变更记录中写明日期、设备/系统、Gateway 环境与结果，再开始阶段 9.5。

## 核验记录

- 2026-08-27 首轮人工核验未通过：Android Studio 启动的 iPhone 17 / iOS 26.5 Simulator 中，重复进入同一会话后出现 `KMP SessionControl 运行期结果失效`，随后模型和权限配置无法加载，`permission-options` 进入 12 秒超时。
- 根因：同一 target 的正常第二次刷新被错误标记为必须显式携带 `sessionId`；兼容 Gateway 省略该字段时响应被忽略。超时隔离分支又遗漏清理 `explicitSessionRequiredKinds`，形成无 active target 的无效快照并触发永久 fail-closed。
- 2026-08-27 第二轮人工核验仍未通过：模型请求持续超时，权限刷新随后直接收到 `response-correlation-quarantined`。进一步对照实际 `dsh-plugin-mobile-gateway` 后确认，`models` 和 `permission-options` 成功帧按协议本来就不回显客户端 request token 或 `sessionId`；永久 quarantine 与强制显式 session 关联均无法兼容真实协议。
- 已改为按 kind 的唯一 active generation 关联：无 `sessionId` 的成功/错误帧绑定当前 active 请求；显式回显但 session 不匹配的帧仍拒绝；超时只结束当前 generation，并允许直接重试或启动 latest queued target，不再要求断线重连。自动化门禁再次通过：KMP 44 项、iOS Simulator 94 项均为 0 失败；Android 单测/APK 与 iPhoneOS Release 构建成功。等待重新执行本清单第 3、4 节，人工结果仍保持“待核验”。
- 2026-08-27 第三轮人工核验发现权限切换失败：宿主返回 `arguments-invalid ... commands/execute ... missing "images"`。移动端发送及 KMP effect 正确，根因是 Mobile Gateway 调用 Typert `commands/execute` 时仍只传 `agentId`/`line`，未满足新版 descriptor 要求的必填 `images` 字段。
- `dsh-plugin-mobile-gateway` 已为 `/permission` 命令显式补充 `images: []`，并强化权限协议断言；Gateway 完整测试通过（53 个 dispatch 用例，以及 setup-ip、auth/pairing、LAN 测试）。等待重启 Gateway 后再次核验权限切换，人工结果仍保持“待核验”。
