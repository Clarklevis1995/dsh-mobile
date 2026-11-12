# 独立模拟器候选v7

2026-11-12，API21 ARM64，源码检查点827c96fa。独立候选preview202611110401从v6原位升级到v7，纳入Native内部MVI补丁和已解码历史/事件直传优化；同版163 JVM/72 Node、元数据/鸿蒙ktlint/实际Native/HAP、两次正式长流内存F及真实历史/离线缓存G见E80/E81，表格横滚与CSV系统保存F见E82。

真实旧v6建立身份/历史/草稿后再安装新包，Asset自动认证、历史及文字草稿保持；本轮唯一临时Host经产品确认移除，原有profile集合保持。所有ArkTS/C++/头文件/Native与生产源文件逐字一致，未包含只读历史/性能探针。HAP内Native与SDK strip-all处理的源库逐字一致，两个哈希见packaged-native-proof.json。正式鸿蒙包未覆盖，旧候选产物未覆盖。

候选HAP约41.2MB，实际完整路径/字节数/SHA256见result.json。这是未作商店签名的模拟器候选，不代替真机、全页V、静态检查或用户最终验收。升级夹具和本轮新增HDC映射finally清理，候选回到首页；专属真实Host/凭据已按E81清理，表格视觉夹具另有独立生命周期。
