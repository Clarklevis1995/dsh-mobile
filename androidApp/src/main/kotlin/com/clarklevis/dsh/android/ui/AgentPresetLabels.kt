package com.clarklevis.dsh.android.ui

/** 与 iOS L10n.presetModeName 保持一致；未知的部署自定义预设继续显示网关名称。 */
internal fun agentPresetDisplayName(id: String?, gatewayName: String? = null): String = when (id) {
    null -> "Agent"
    "standard" -> "标准模式"
    "code" -> "PTC 模式"
    "minimal" -> "极简模式"
    "cordis" -> "创造模式"
    else -> gatewayName?.takeIf(String::isNotBlank) ?: id
}
