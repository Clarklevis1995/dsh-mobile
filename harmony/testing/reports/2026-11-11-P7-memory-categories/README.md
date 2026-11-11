# 长流式内存分类诊断，非验收通过

2026-11-11，API21 ARM64，优化生产Native/ArkUI，仅临时smoke注入SDK只读仪表。沿用1005历史、两轮55274字符代码/表格流、草稿/阅读/前后台，未修改门禁数值、Worker空闲2秒、GC设置或系统内存压力。系统分类使用hidumper --mem/--mem-smaps和线程表，采样前核对唯一PID与包名，不做heap dump/GC。

诊断观测：系统分类初始约175.9MiB，第二轮前后台后约226.7MiB；匿名其他映射约47.2→86.9MiB，Native堆59.9→73.0MiB，ArkTS堆37.0→32.7MiB。之后30秒约174.3MiB，匿名其他降至22.2MiB。说明本轮短时增长主要落在匿名其他映射，不能仅从主线程ArkTS堆或Worker猜测，也不能据此证明是Kotlin GC、泄漏或某特定分配器；需继续核对Native分配/回收。

重要：分类+smaps+线程表每组约4秒，第二轮after-background-1采样后才进入原脚本idle计时，因此本轮“final-idle-3s”已晚于原无诊断相位。原runner输出了算术PASS，但它不具备关闭原3秒门禁的效力。原日志/JSON完整保留；categories-summary.json明确acceptanceEligible=false。后续脚本对诊断模式明确仅输出DIAGNOSTIC_ONLY并标记不具备验收资格，正常模式沿用原阈值与断言，不将诊断开销冒称优化。P7-06b继续未勾选。

Native终帧长度/FNV唯一性、阅读锚点和草稿断言通过。本轮无产品修改；独立候选仍v6。未并行LLVM或其他设备操作，测试服务/新增映射/独立smoke在finally清理。
