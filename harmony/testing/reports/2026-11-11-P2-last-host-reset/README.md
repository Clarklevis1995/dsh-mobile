# 移除最后主机后的空状态F

2026-11-11，独立smoke/API21 ARM64，85bf6897后本轮修复。候选v3试用观察到最后Host移除后status仍为旧“已断开”，SettingsData仍可能引用移除前的endpoint/控制值。移除活动Host或删除全部后，明确重置workspace/status/error并立即同步空设置快照；不改认证、缓存或共享Reducer。

真实产品F：旧Host保存草稿→移除唯一Host→设置无旧地址且默认值禁用、状态尚未连接→首页未连接且无旧会话→迟到回调2秒后和force-stop冷启不复活→同Host重新配对草稿为空→删除全部再验首页/设置。全部通过，原图和日志已保存。仅删除独立smoke本轮资料，不碰正式包或独立候选。
