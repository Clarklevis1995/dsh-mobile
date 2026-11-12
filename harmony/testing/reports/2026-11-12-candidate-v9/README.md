# 独立模拟器候选v9

2026-11-12，API21 ARM64/127.0.0.1:5557，生产源码c2c67b44。独立preview202611110401从v8原位升级至v9，纳入表格系统字号热更新及fp行框测量修复；E84预览视觉/旋转/CSV与E86真实字号标准→最大→标准K/F、修复后正式长流原8门禁均已通过。

实际旧v8先建立本轮Host身份、历史与草稿，再安装新v9；Asset自动认证、历史与文字草稿保持。仅经产品移除本轮临时Host，原有profile集合完全一致，最终回首页；专属升级夹具与新增HDC映射finally清理。正式包和旧候选产物未覆盖。

95份生产ArkTS/C++/Native文件逐字比对，无只读测试探针；HAP中Native与SDK strip-all处理源库字节完全相同，见source-manifest.json和packaged-native-proof.json。实际HAP路径、41.27MB字节数和SHA256见result.json，归档时再次按文件实际字节校验。

首次在长流夹具退出后立即运行，端口预检因TCP TIME_WAIT失败，尚未创建profile或安装新包。lsof确认18783没有真实监听者；与夹具相同启用SO_REUSEADDR避免TIME_WAIT误报，独立真实listening socket试验证明仍拒绝占用端口，随后完整重跑通过。原失败日志保留，没有终止其他服务。

这是未签商店证书的模拟器候选。全页视觉、其他页面字号与读屏、宽屏、静态检查债务、真机及用户签收仍按唯一规划书保持待办。
