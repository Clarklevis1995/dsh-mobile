# 设置主机描述信息

2026-11-11，冻结1.7.12独立Android参考连接本轮0.12真实Host后，发现参考中的DSH Host信息卡尚未映射到鸿蒙。

现在控制通道hello后用共享GatewayRequests请求host，共享GatewayWireDecoder/HostSnapshot DTO投影版本、已连接会话和工作目录；设置卡按服务端实际字段显示，缺失版本显示“未提供”，空cwd不显示，不猜测会话数。断开/新控制握手清空旧主机信息，非控制通道不能覆盖。@ObjectLink实时更新卡片。

新增K验证请求、真实字段、通道隔离及断开清理；106 JVM/53 Node和release Native/HAP通过。独立API21模拟器F确认fixture的0.2.2-fixture/会话数/cwd可见，断开/重连/Ping和明暗导航通过。原始设置根页截图可能经滚动，不能取代全页V。后续字号样式修正另行验收。
