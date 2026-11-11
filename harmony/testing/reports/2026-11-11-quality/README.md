# 当前核心、格式与生产构建

2026-11-11：文案生成一致性、106 JVM、58 Node和鸿蒙独立源码全量ktlint1.5.0通过。ktlint先报告14处条件换行和1处未使用导入，按规则修复后无输出/exit0；红绿日志保留。当前优化Native publish与ArkTS/HAP完整构建成功，生产源码无测试探针，未安装正式包。最新构建以post-format日志、production-artifact.json路径和校验值为准；早先构建日志仅保留当轮历史。

实际detekt1.23.8独立CLI默认规则仍exit2，235 weighted issues：83长行、72数值常量、25返回数量、17复杂条件、17圈复杂度、10长方法、7函数数量及其他结构规则。完整结果在detekt-default-findings.txt。input=harmony/shared/src/commonMain/kotlin，未启用type-resolution/classpath，无baseline/覆盖配置；此前135条缺少精确命令的历史报告保留，不能直接作增量比较。P8-01静态门禁继续待办。

CLI参数及退出码按[官方固定版本文档](https://detekt.dev/docs/1.23.8/gettingstarted/cli/)。工具来自Maven Central固定坐标io.gitlab.arturbosch.detekt:detekt-cli:1.23.8:all，未改变主项目Gradle。复现：

```sh
java -jar /path/to/ktlint-1.5.0-all.jar 'harmony/shared/src/**/*.kt'
java -jar /path/to/detekt-cli-1.23.8-all.jar --input harmony/shared/src/commonMain/kotlin --base-path . --report txt:/tmp/harmony-detekt.txt
scripts/build-harmony.sh test
scripts/build-harmony.sh all
```

F/G/V按规划书E编号单独记录；本报告不替代全页视觉、真机或最终用户验收。
