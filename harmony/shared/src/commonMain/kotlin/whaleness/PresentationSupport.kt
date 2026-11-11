package whaleness

import com.clarklevis.dsh.shared.facade.CodeSyntaxHighlighting
import com.clarklevis.dsh.shared.facade.ConversationFileReferences
import com.clarklevis.dsh.shared.facade.MarkdownTableExport
import com.clarklevis.dsh.shared.protocol.wireJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class HarmonyCodeSpan(
    val start: Int,
    val end: Int,
    val light: String,
    val dark: String,
)

@Serializable
data class HarmonyPresentation(
    val language: String = "",
    val spans: List<HarmonyCodeSpan> = emptyList(),
    val linkKind: String = "invalid",
    val target: String = "",
    val plainText: String = "",
    val csv: String = "",
)

/** 平台只排版 AST；高亮、文件引用和导出规则继续使用共享实现。 */
internal object PresentationSupport {
    fun render(command: HarmonyCommand): HarmonyPresentation =
        when (command.op) {
            "render-code" -> {
                HarmonyPresentation(
                    language = CodeSyntaxHighlighting.language(command.title),
                    spans =
                        CodeSyntaxHighlighting.highlight(command.text, command.title).map {
                            HarmonyCodeSpan(
                                it.start,
                                it.endExclusive,
                                color(CodeSyntaxHighlighting.color(it.kind, false)),
                                color(CodeSyntaxHighlighting.color(it.kind, true)),
                            )
                        },
                )
            }

            "render-link" -> {
                val value = command.text.trim()
                val path = ConversationFileReferences.filePath(value)
                when {
                    value.any { it.isISOControl() } -> {
                        HarmonyPresentation()
                    }

                    Regex("^https?://[^/?#\\s]+(?:[/?#].*)?$", RegexOption.IGNORE_CASE).matches(value) -> {
                        HarmonyPresentation(linkKind = "web", target = value)
                    }

                    path != null && path.none { it.isISOControl() } -> {
                        HarmonyPresentation(linkKind = "file", target = path)
                    }

                    else -> {
                        HarmonyPresentation()
                    }
                }
            }

            "render-table" -> {
                HarmonyPresentation(
                    plainText = MarkdownTableExport.plainText(command.rows),
                    csv = MarkdownTableExport.csv(command.rows),
                )
            }

            else -> {
                error("不支持的内容操作")
            }
        }

    private fun color(value: Int): String = "#" + value.toUInt().toString(16).padStart(8, '0')
}

/** 无会话状态的独立入口，可从平台工作线程调用，不接触 Engine 注册表。 */
fun renderContent(json: String): String =
    runCatching {
        require(json.length <= 1024 * 1024) { "内容超出处理预算" }
        val command = wireJson.decodeFromString<HarmonyCommand>(json)
        wireJson.encodeToString(HarmonyResult(presentation = PresentationSupport.render(command)))
    }.getOrElse { wireJson.encodeToString(HarmonyResult(ok = false, error = "内容处理失败")) }
