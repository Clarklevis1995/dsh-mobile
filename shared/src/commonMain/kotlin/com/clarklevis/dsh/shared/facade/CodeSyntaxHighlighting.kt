package com.clarklevis.dsh.shared.facade

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 聊天代码块的共用入口。偏移采用 UTF-16，原始缩进、换行及 Unicode 不做改写。 */
object CodeSyntaxHighlighting {
    private const val MAX_HIGHLIGHT_CHARACTERS = 200_000
    private val supportedLanguages =
        setOf(
            "c",
            "cpp",
            "csharp",
            "css",
            "dart",
            "diff",
            "go",
            "groovy",
            "html",
            "java",
            "javascript",
            "json",
            "kotlin",
            "latex",
            "lua",
            "objective-c",
            "php",
            "properties",
            "python",
            "ruby",
            "rust",
            "shell",
            "sql",
            "swift",
            "toml",
            "typescript",
            "xml",
            "yaml",
        )
    private val aliases =
        mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "gradle" to "groovy",
            "js" to "javascript",
            "jsx" to "javascript",
            "mjs" to "javascript",
            "ts" to "typescript",
            "tsx" to "typescript",
            "c++" to "cpp",
            "cc" to "cpp",
            "cxx" to "cpp",
            "h" to "c",
            "hpp" to "cpp",
            "c#" to "csharp",
            "cs" to "csharp",
            "py" to "python",
            "rb" to "ruby",
            "rs" to "rust",
            "golang" to "go",
            "sh" to "shell",
            "bash" to "shell",
            "zsh" to "shell",
            "shellscript" to "shell",
            "htm" to "html",
            "vue" to "html",
            "svg" to "xml",
            "plist" to "xml",
            "scss" to "css",
            "sass" to "css",
            "jsonc" to "json",
            "json5" to "json",
            "yml" to "yaml",
            "ini" to "properties",
            "conf" to "properties",
            "tex" to "latex",
            "patch" to "diff",
            "objc" to "objective-c",
            "objectivec" to "objective-c",
        )

    fun language(info: String?): String {
        val label =
            info
                .orEmpty()
                .trim()
                .takeWhile { !it.isWhitespace() }
                .removePrefix("{.")
                .removeSuffix("}")
                .removePrefix("language-")
                .lowercase()
        val canonical = aliases[label] ?: label
        return canonical.takeIf { it in supportedLanguages } ?: "plaintext"
    }

    fun highlight(
        source: String,
        info: String?,
    ): List<WorkspaceCodeToken> {
        if (source.length > MAX_HIGHLIGHT_CHARACTERS) return emptyList()
        var language = language(info)
        // 仅无标签时识别完整 JSON，不把普通文本或未知语言猜成另一种编程语言。
        if (info.isNullOrBlank() && source.trimStart().firstOrNull() in setOf('{', '[')) {
            val json = runCatching { Json.parseToJsonElement(source) }.getOrNull()
            if (json is JsonObject || json is JsonArray) language = "json"
        }
        return WorkspaceCodePreviewSupport.tokenize(source, language)
    }

    private val palette =
        mapOf(
            WorkspaceCodeTokenKind.COMMENT to (0xFF626A73.toInt() to 0xFF8B949E.toInt()),
            WorkspaceCodeTokenKind.STRING to (0xFF0A7A3D.toInt() to 0xFFA5D6FF.toInt()),
            WorkspaceCodeTokenKind.KEYWORD to (0xFF8250DF.toInt() to 0xFFFF7B72.toInt()),
            WorkspaceCodeTokenKind.NUMBER to (0xFF0550AE.toInt() to 0xFF79C0FF.toInt()),
            WorkspaceCodeTokenKind.TYPE to (0xFF953800.toInt() to 0xFFD2A8FF.toInt()),
            WorkspaceCodeTokenKind.TAG to (0xFFCF222E.toInt() to 0xFF7EE787.toInt()),
            WorkspaceCodeTokenKind.ATTRIBUTE to (0xFF953800.toInt() to 0xFFFFA657.toInt()),
        )

    /** 两端直接使用相同的 sRGB 色值；主题变化只更换颜色，不改动代码内容。 */
    fun color(
        kind: WorkspaceCodeTokenKind,
        dark: Boolean,
    ): Int {
        val colors = palette.getValue(kind)
        return if (dark) colors.second else colors.first
    }
}
