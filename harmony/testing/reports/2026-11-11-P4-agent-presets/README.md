# Agent预设页恢复与浅深对照

2026-11-11，API21 ARM64独立smoke，生产Native与原生ArkUI。补齐冻结参考页内22fp标题、说明、17fp卡片标题、预设ID胶囊、黑白当前徽标、边框与卡片间距；请求复用共享Control Store，可见页在连接恢复与Ability前台恢复时重新查询。空目录与错误均有可用重试；损坏预设禁用。

F通过：确认前/取消零set-default；确认、重开保持；服务端保存失败保留旧值；损坏禁用；空目录/不支持重试；实际停止本轮TCP服务后禁用操作，恢复服务且默认build→read时读回新值，不自动写入；真实Home期间服务恢复build后回原页读回；冷启动与真实浅深切换。只操作product_ui创建的子进程，finally清理替代进程和本轮映射。原图、JSON和测试副本源码哈希保留。

V通过限定范围：独立Android冻结9e13dde3（1.7.12），376逻辑宽/标准字号，同源静态build/read目录与build默认；浅深各23项标签纵向/部分左缘与两卡矩形边缘，全部≤2vp。系统字体导致字形、等宽标签宽度和说明断行差异保留；系统栏、返回按钮系统触摸框、全部异常V与无障碍未整体关闭。见comparison.html及geometry JSON。

K/构建：72 Node、106 JVM任务通过（未改Kotlin时UP-TO-DATE），生产Native发布与ArkTS/HAP编译通过；无测试探针进入产品。真实Host的预设继承/锁定G另记录。

真实G通过：Gateway0.12.0、DSH0.2.2-rc.1，真实Agent预设服务/会话和受控本机上游。取消minimal保持standard，确认后持久且客户端冷启动仍minimal；已执行standard会话不变，新建空会话继承minimal；从原生会话控制改standard后权威服务确认，发送真实消息后服务端locked=true且原生按钮禁用。结束通过原生UI恢复standard，重新查询确认。只操作本轮专属Host，真实G与F/V分别记录。
