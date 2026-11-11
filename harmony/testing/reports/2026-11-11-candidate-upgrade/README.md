# 独立候选首次安装与原位升级F

2026-11-11，Mate70Pro/API21 ARM64。本轮新包com.clarklevis.whaleness.harmony.preview202611110401，桌面名称“Whaleness 鸿蒙候选”，未覆盖正式包或先前候选。候选生产ArkTS/C++/头文件/Native库与当前源码逐字相等，manifest已记录；仅包名、名称与版本元数据不同，没有测试入口、夹具地址或固定设备适配器。

首次安装vCode1→连接独立夹具→保存文字草稿/稳定历史、深色/150%偏好→实际force-stop→构建vCode2并hdc install -r原位更新→重开。实际自动认证成功、历史可读、草稿逐字保留、深色/150%仍在，F通过。此项只证明当前同一实现的包更新保留数据，不替代旧Native/schema兼容；RDBv1→v3专项另见E18。

结束恢复浅色/100%，通过产品移除本轮测试Host，首页为空且无测试凭据；夹具已停止、仅本轮端口映射已清理。候选保留安装，已查看升级结果和清洁首页原图。产物绝对路径、40MB大小、SHA256和包名见result.json，HAP放在harmonyApp/build/acceptance（构建产物不入Git）。

模拟器安装/启动：

```sh
hdc -t 127.0.0.1:5557 install -r harmonyApp/build/acceptance/com.clarklevis.whaleness.harmony.preview202611110401-unsigned.hap
hdc -t 127.0.0.1:5557 shell aa start -a EntryAbility -b com.clarklevis.whaleness.harmony.preview202611110401
```

在首页选择主机→添加主机，使用自己的网关地址/一次性配对内容连接。HAP为优化Native/debug unsigned模拟器候选；真机需要本机DevEco配置签名。完整全页V、静态结构整改、所有设备D和用户最终验收仍待完成。
