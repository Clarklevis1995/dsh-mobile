# 实际窗口逻辑宽屏验收

2026-11-11，API21 ARM64/Mate70Pro独立smoke。临时副本在EntryAbility采用官方WindowStage.setCustomDensity(2)，生产断点仍为900vp/500vp和字体缩放<1.5，不改系统密度、设备配置或产品断点。实际物理方向切换为2832x1316像素，应用根区域测得1416x609vp，明确触发wide双栏；手机默认density3.5横屏只有约809vp，不能代替该项。

F通过：658x1299vp竖屏单栏→1416x609vp宽屏侧栏/聊天双栏；A/B会话选择与各自草稿隔离，A返回逐字恢复；回竖屏侧栏收起恢复菜单入口。150%字号下同1416x609vp安全回退单栏，草稿保持，再回竖屏。附图已检查双栏实际可见，尺寸/状态原值见wide-layout.json。

探针只注入临时副本，卸载即可移除，不修改用户模拟器系统设置。本项不是平板硬件、自由分屏、键盘完整矩阵或同内容Android宽屏V。

官方接口：[WindowStage.setCustomDensity](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/arkts-apis-window-windowstage/)，本机SDK since15/20签名已核对。
