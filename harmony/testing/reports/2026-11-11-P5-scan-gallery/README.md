# 默认系统扫码图库到实际配对

2026-11-11，API21 Mate70Pro ARM64独立smoke，独立18783夹具F。临时入口只负责把一张本轮唯一标题PNG二维码经系统保存确认导入图库，不替换ScanKit、Native校验、GatewayClient、Asset或配对流程。二维码内容来自本轮夹具；未归档二维码图或配对内容。

实际打开scanBarcode.startScanForResult→系统“图库”→确认只有一张CachedImage且URI等于本轮记录→选中→系统“完成”→ScanKit返回原文→共享prepare验证→自动认证。网关记录fixture-pair-accepted control，首页实际显示已连接。随后真实force-stop/启动，凭据恢复、进入会话发送echo并收到完整流式回复，图片均已查看。

结束仅按本轮记录URI删除该媒体。相册使用唯一标题，避免已删同名媒体返回-203；初始系统节点探索和遗漏“完成”步骤不计通过，最终完整结果见results.json。之前图像decode有效/过期/无效三项和扫码取消见E43。本项不代替相机拍码D、错误系统权限全矩阵或真实Host扫码G。
