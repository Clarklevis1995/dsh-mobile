# ScanKit 模拟器兼容性与系统取消

2026-11-11 API21 ARM64模拟器，独立smoke包。KMP配对规则不重复实现。

实际generateBarcode返回 `Emulator is not supported.`，canIUse为true仍存在这一实现限制。原始失败日志保留。测试夹具改由本机CoreImage生成PNG二维码（仅本轮固定测试内容），鸿蒙ImageKit实际读PNG，再由ScanKit detectBarcode.decode识别，逐字核对原文，交给Native prepare校验有效／过期／无效配对内容，三项均通过。仅码图生成记SKIP，图像识码为实际F通过。

产品实际startScanForResult打开系统“扫描二维码/条形码”界面，有安全相机提示和图库入口；系统Back取消回到授权配对，配对输入和地址逐字保留，按钮可再次使用，夹具未收到配对。未截取相机取景画面，仅归档取消后表单。此结论不包含摄像头对焦、拍摄真实二维码、权限撤销或真机D，也未包含系统扫码图库识别后自动配对。

当前华为文档说明API20起支持默认扫码界面；部分接口的模拟器支持需逐个实测，不能用旧文档概括ScanKit整体不可用。官方来源：[统一扫码服务概述](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/scan-introduction/)、[默认界面扫码](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/scan-scanbarcode/)。
