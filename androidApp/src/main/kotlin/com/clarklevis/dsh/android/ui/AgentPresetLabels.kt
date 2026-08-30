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

/** 与 iOS L10n.presetModeBlurb 保持一致，网关有描述时优先展示网关内容。 */
internal fun agentPresetDisplayDescription(id: String, gatewayDescription: String?): String =
    gatewayDescription?.takeIf(String::isNotBlank) ?: when (id) {
        "standard" -> "功能完整的编码 Agent，支持文件编辑、Shell、检索、Skills、计划与工作流。"
        "code" -> "通过 Code Mode SDK 组合多步工具操作。"
        "minimal" -> "精简工具集合，适合轻量、直接的编码任务。"
        "cordis" -> "用于创建和维护自定义 Agent 预设。"
        else -> "由 DeepSeek Harness 提供的 Agent 预设。"
    }
