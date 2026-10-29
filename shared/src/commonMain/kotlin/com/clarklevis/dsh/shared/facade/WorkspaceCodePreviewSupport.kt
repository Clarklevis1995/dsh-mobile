package com.clarklevis.dsh.shared.facade

enum class WorkspaceCodeTokenKind {
    COMMENT,
    STRING,
    KEYWORD,
    NUMBER,
    TYPE,
    TAG,
    ATTRIBUTE
}

data class WorkspaceCodeToken(
    val start: Int,
    val endExclusive: Int,
    val kind: WorkspaceCodeTokenKind
)

data class WorkspaceCodeDocument(
    val text: String,
    val language: String,
    val languageDisplayName: String,
    val tokens: List<WorkspaceCodeToken>,
    val lineCount: Int
)

object WorkspaceCodePreviewSupport {
    private const val TAB_WIDTH = 4

    fun isSupported(name: String, mediaType: String?): Boolean =
        languageFor(name, mediaType) != null

    fun languageFor(name: String, mediaType: String?): String? {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        EXTENSION_LANGUAGES[extension]?.let { return it }
        return MEDIA_TYPE_LANGUAGES[mediaType?.substringBefore(';')?.trim()?.lowercase()]
    }

    fun prepare(source: String, name: String, mediaType: String?): WorkspaceCodeDocument {
        val language = languageFor(name, mediaType) ?: "plaintext"
        val text = normalizeIndentation(source)
        return WorkspaceCodeDocument(
            text = text,
            language = language,
            languageDisplayName = LANGUAGE_DISPLAY_NAMES[language] ?: language,
            tokens = tokenize(text, language),
            lineCount = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
        )
    }

    fun normalizeIndentation(source: String): String = source
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .joinToString("\n", transform = ::expandTabs)

    private fun expandTabs(line: String): String = buildString(line.length) {
        var column = 0
        line.forEach { character ->
            if (character == '\t') {
                val spaces = TAB_WIDTH - (column % TAB_WIDTH)
                repeat(spaces) { append(' ') }
                column += spaces
            } else {
                append(character)
                column++
            }
        }
    }

    internal fun tokenize(text: String, language: String): List<WorkspaceCodeToken> {
        if (text.isEmpty() || language == "plaintext") return emptyList()
        if (language in MARKUP_LANGUAGES) return tokenizeMarkup(text)
        if (language == "latex") return tokenizeLatex(text)
        if (language == "diff") return tokenizeDiff(text)
        val occupied = BooleanArray(text.length)
        val tokens = mutableListOf<WorkspaceCodeToken>()

        fun addRange(start: Int, endExclusive: Int, kind: WorkspaceCodeTokenKind) {
            if (start !in text.indices || endExclusive <= start || endExclusive > text.length) return
            var index = start
            while (index < endExclusive) {
                if (occupied[index]) return
                index++
            }
            index = start
            while (index < endExclusive) {
                occupied[index] = true
                index++
            }
            tokens += WorkspaceCodeToken(start, endExclusive, kind)
        }

        fun addMatches(regex: Regex, kind: WorkspaceCodeTokenKind) {
            regex.findAll(text).forEach { match ->
                addRange(match.range.first, match.range.last + 1, kind)
            }
        }

        fun addGroupMatches(regex: Regex, groupIndex: Int, kind: WorkspaceCodeTokenKind) {
            regex.findAll(text).forEach { match ->
                val range = match.groups[groupIndex]?.range ?: return@forEach
                addRange(range.first, range.last + 1, kind)
            }
        }

        tokenizeStringsAndComments(text, language).forEach { token ->
            addRange(token.start, token.endExclusive, token.kind)
        }

        if (language == "css") {
            addGroupMatches(CSS_PROPERTY_PATTERN, 1, WorkspaceCodeTokenKind.ATTRIBUTE)
            addGroupMatches(CSS_SELECTOR_PATTERN, 1, WorkspaceCodeTokenKind.TAG)
        }
        if (language in setOf("yaml", "toml", "properties")) {
            addGroupMatches(CONFIG_KEY_PATTERN, 1, WorkspaceCodeTokenKind.ATTRIBUTE)
        }
        if (language == "shell") {
            addMatches(SHELL_VARIABLE_PATTERN, WorkspaceCodeTokenKind.ATTRIBUTE)
        }

        val keywords = when (language) {
            "groovy" -> LANGUAGE_KEYWORDS["java"].orEmpty() + setOf("def", "in", "as", "trait")
            "objective-c" -> C_KEYWORDS + setOf("interface", "implementation", "end", "property", "synthesize", "protocol", "selector", "self", "super", "nil", "YES", "NO")
            "json", "yaml", "toml" -> setOf("true", "false", "null")
            else -> LANGUAGE_KEYWORDS[language].orEmpty()
        }
        if (keywords.isNotEmpty()) {
            addMatches(
                Regex(
                    "\\b(?:${keywords.joinToString("|") { Regex.escape(it) }})\\b",
                    if (language == "sql") RegexOption.IGNORE_CASE else RegexOption.MULTILINE
                ),
                WorkspaceCodeTokenKind.KEYWORD
            )
        }
        addMatches(TYPE_PATTERN, WorkspaceCodeTokenKind.TYPE)

        // Kotlin/Native 的正则后向断言也会在长文本上退化，直接检查数字两侧边界。
        var index = 0
        while (index < text.length) {
            if (!occupied[index] && text[index].isDigit() &&
                (index == 0 || !text[index - 1].isIdentifierPart())
            ) {
                val end = numberTokenEnd(text, index)
                if (end == text.length || !text[end].isIdentifierPart()) {
                    addRange(index, end, WorkspaceCodeTokenKind.NUMBER)
                }
                index = end
            } else {
                index++
            }
        }

        return tokens.sortedBy(WorkspaceCodeToken::start)
    }

    /**
     * 重复分组的字符串正则会在 Kotlin/Native 中逐字符递归，长字符串可耗尽线程栈。
     * 使用只前进的扫描器，未闭合的字符串和块注释高亮至文件末尾，避免反复回溯。
     */
    private fun tokenizeStringsAndComments(text: String, language: String): List<WorkspaceCodeToken> {
        val tokens = mutableListOf<WorkspaceCodeToken>()
        var index = 0
        while (index < text.length) {
            val start = index
            val kind: WorkspaceCodeTokenKind
            when {
                (text.startsWith("<!--", index) && language == "javascript") ||
                    (text.startsWith("/*", index) && language in BLOCK_COMMENT_LANGUAGES) ||
                    (text.startsWith("--[[", index) && language == "lua") -> {
                    val isHtmlComment = text.startsWith("<!--", index)
                    val isLuaComment = text.startsWith("--[[", index)
                    val delimiter = if (isHtmlComment) "-->" else if (isLuaComment) "]]" else "*/"
                    val close = text.indexOf(delimiter, index + if (isHtmlComment || isLuaComment) 4 else 2)
                    index = if (close < 0) text.length else close + delimiter.length
                    kind = WorkspaceCodeTokenKind.COMMENT
                }
                (text.startsWith("//", index) && language in LINE_COMMENT_LANGUAGES) ||
                    (text[index] == '#' && language in HASH_COMMENT_LANGUAGES) ||
                    (text.startsWith("--", index) && language in setOf("sql", "lua")) -> {
                    val close = text.indexOf('\n', index)
                    index = if (close < 0) text.length else close
                    kind = WorkspaceCodeTokenKind.COMMENT
                }
                text[index] == '"' || text[index] == '\'' || text[index] == '`' -> {
                    val quote = text[index]
                    // Rust 的生命周期 'a / 'static 不属于字符字面量。
                    if (language == "rust" && quote == '\'' && index + 1 < text.length &&
                        text[index + 1].isLetter() && text.getOrNull(index + 2) != '\''
                    ) { index++; continue }
                    val delimiter = if (quote != '`' &&
                        index + 2 < text.length && text[index + 1] == quote && text[index + 2] == quote
                    ) {
                        quote.toString().repeat(3)
                    } else {
                        quote.toString()
                    }
                    index = quotedTokenEnd(text, index, delimiter)
                    kind = if (language == "json" && quote == '"' &&
                        nextNonWhitespaceCharacter(text, index) == ':'
                    ) {
                        WorkspaceCodeTokenKind.ATTRIBUTE
                    } else {
                        WorkspaceCodeTokenKind.STRING
                    }
                }
                else -> {
                    index++
                    continue
                }
            }
            tokens += WorkspaceCodeToken(start, index, kind)
        }
        return tokens
    }

    private fun tokenizeLatex(text: String): List<WorkspaceCodeToken> = buildList {
        var index = 0
        while (index < text.length) {
            val start = index
            val kind = when {
                text[index] == '%' -> {
                    index = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                    WorkspaceCodeTokenKind.COMMENT
                }
                text[index] == '\\' -> {
                    index++
                    if (index < text.length && text[index].isLetter()) {
                        while (index < text.length && text[index].isLetter()) index++
                    } else if (index < text.length) { index++ }
                    WorkspaceCodeTokenKind.KEYWORD
                }
                text[index] in "{}[]$&_^" -> { index++; WorkspaceCodeTokenKind.ATTRIBUTE }
                text[index].isDigit() -> {
                    index = numberTokenEnd(text, index)
                    WorkspaceCodeTokenKind.NUMBER
                }
                else -> { index++; continue }
            }
            add(WorkspaceCodeToken(start, index, kind))
        }
    }

    private fun tokenizeDiff(text: String): List<WorkspaceCodeToken> = buildList {
        var start = 0
        while (start < text.length) {
            val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
            val kind = when {
                text.startsWith("@@", start) || text.startsWith("diff ", start) -> WorkspaceCodeTokenKind.ATTRIBUTE
                text[start] == '+' -> WorkspaceCodeTokenKind.STRING
                text[start] == '-' -> WorkspaceCodeTokenKind.TAG
                else -> null
            }
            if (kind != null) add(WorkspaceCodeToken(start, end, kind))
            start = end + 1
        }
    }

    private fun quotedTokenEnd(text: String, start: Int, delimiter: String): Int {
        var index = start + delimiter.length
        while (index < text.length) {
            when {
                text[index] == '\\' -> index += if (index + 1 < text.length) 2 else 1
                text.startsWith(delimiter, index) -> return index + delimiter.length
                else -> index++
            }
        }
        return text.length
    }

    private fun numberTokenEnd(text: String, start: Int): Int {
        var index = start
        if (index + 2 < text.length && text[index] == '0' &&
            (text[index + 1] == 'x' || text[index + 1] == 'X') && text[index + 2].isHexDigit()
        ) {
            index += 2
            while (index < text.length && text[index].isHexDigit()) index++
            return index
        }
        while (index < text.length && text[index].isDigit()) index++
        if (index + 1 < text.length && text[index] == '.' && text[index + 1].isDigit()) {
            index++
            while (index < text.length && text[index].isDigit()) index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            var exponent = index + 1
            if (text.getOrNull(exponent) == '+' || text.getOrNull(exponent) == '-') exponent++
            val digits = exponent
            while (exponent < text.length && text[exponent].isDigit()) exponent++
            if (exponent > digits) index = exponent
        }
        return index
    }

    /**
     * Kotlin/Native 的多轮正则扫描在几百行 HTML 上会出现数量级退化。
     * 标记语言改用单遍词法扫描，同时保留标签、属性、字符串、注释和数字高亮。
     */
    private fun tokenizeMarkup(text: String): List<WorkspaceCodeToken> {
        val tokens = mutableListOf<WorkspaceCodeToken>()
        var index = 0

        fun add(start: Int, endExclusive: Int, kind: WorkspaceCodeTokenKind) {
            if (start >= 0 && endExclusive > start && endExclusive <= text.length) {
                tokens += WorkspaceCodeToken(start, endExclusive, kind)
            }
        }

        fun quotedEnd(start: Int, quote: Char): Int {
            var cursor = start + 1
            while (cursor < text.length) {
                if (text[cursor] == '\\' && cursor + 1 < text.length) {
                    cursor += 2
                    continue
                }
                if (text[cursor] == quote) return cursor + 1
                cursor++
            }
            return text.length
        }

        while (index < text.length) {
            when {
                text.startsWith("<!--", index) -> {
                    val close = text.indexOf("-->", startIndex = index + 4)
                    val end = if (close < 0) text.length else close + 3
                    add(index, end, WorkspaceCodeTokenKind.COMMENT)
                    index = end
                }
                text.startsWith("/*", index) -> {
                    val close = text.indexOf("*/", startIndex = index + 2)
                    val end = if (close < 0) text.length else close + 2
                    add(index, end, WorkspaceCodeTokenKind.COMMENT)
                    index = end
                }
                text.startsWith("//", index) -> {
                    val close = text.indexOf('\n', startIndex = index + 2)
                    val end = if (close < 0) text.length else close
                    add(index, end, WorkspaceCodeTokenKind.COMMENT)
                    index = end
                }
                text[index] == '<' -> {
                    index = tokenizeMarkupTag(text, index, tokens)
                }
                text[index] == '"' || text[index] == '\'' || text[index] == '`' -> {
                    val end = quotedEnd(index, text[index])
                    add(index, end, WorkspaceCodeTokenKind.STRING)
                    index = end
                }
                text[index].isDigit() && (index == 0 || !text[index - 1].isIdentifierPart()) -> {
                    val end = numberTokenEnd(text, index)
                    if (end == text.length || !text[end].isIdentifierPart()) {
                        add(index, end, WorkspaceCodeTokenKind.NUMBER)
                    }
                    index = end.coerceAtLeast(index + 1)
                }
                text[index].isUpperCase() && (index == 0 || !text[index - 1].isIdentifierPart()) -> {
                    var end = index + 1
                    while (end < text.length && text[end].isIdentifierPart()) end++
                    add(index, end, WorkspaceCodeTokenKind.TYPE)
                    index = end
                }
                else -> index++
            }
        }
        return tokens
    }

    private fun tokenizeMarkupTag(
        text: String,
        start: Int,
        tokens: MutableList<WorkspaceCodeToken>
    ): Int {
        var index = start + 1
        if (index < text.length && text[index] == '/') index++
        while (index < text.length && text[index].isWhitespace()) index++
        if (index < text.length && (text[index] == '!' || text[index] == '?')) index++
        while (index < text.length && text[index].isWhitespace()) index++

        val tagStart = index
        while (index < text.length && text[index].isMarkupNamePart()) index++
        if (index > tagStart) {
            val kind = if (text.regionMatches(tagStart, "DOCTYPE", 0, 7, ignoreCase = true)) {
                WorkspaceCodeTokenKind.KEYWORD
            } else {
                WorkspaceCodeTokenKind.TAG
            }
            tokens += WorkspaceCodeToken(tagStart, index, kind)
        }

        while (index < text.length && text[index] != '>') {
            when {
                text[index] == '"' || text[index] == '\'' -> {
                    val quote = text[index]
                    var end = index + 1
                    while (end < text.length) {
                        if (text[end] == '\\' && end + 1 < text.length) {
                            end += 2
                            continue
                        }
                        if (text[end] == quote) {
                            end++
                            break
                        }
                        end++
                    }
                    tokens += WorkspaceCodeToken(index, end, WorkspaceCodeTokenKind.STRING)
                    index = end
                }
                text[index].isMarkupNameStart() -> {
                    val attributeStart = index
                    index++
                    while (index < text.length && text[index].isMarkupNamePart()) index++
                    var lookahead = index
                    while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                    if (lookahead < text.length && text[lookahead] == '=') {
                        tokens += WorkspaceCodeToken(
                            attributeStart,
                            index,
                            WorkspaceCodeTokenKind.ATTRIBUTE
                        )
                    }
                }
                else -> index++
            }
        }
        return if (index < text.length) index + 1 else text.length
    }

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

    private fun Char.isMarkupNameStart(): Boolean = isLetter() || this == '_' || this == ':'

    private fun Char.isMarkupNamePart(): Boolean =
        isLetterOrDigit() || this == '_' || this == ':' || this == '-' || this == '.'

    private fun Char.isHexDigit(): Boolean =
        isDigit() || this in 'a'..'f' || this in 'A'..'F'

    private fun nextNonWhitespaceCharacter(text: String, start: Int): Char? {
        var index = start
        while (index < text.length) {
            if (!text[index].isWhitespace()) return text[index]
            index++
        }
        return null
    }

    private val EXTENSION_LANGUAGES = mapOf(
        "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "cxx" to "cpp", "hpp" to "cpp",
        "cs" to "csharp", "css" to "css", "scss" to "css", "sass" to "css",
        "dart" to "dart", "go" to "go", "gradle" to "kotlin", "groovy" to "groovy",
        "htm" to "html", "html" to "html", "java" to "java", "js" to "javascript",
        "jsx" to "javascript", "json" to "json", "jsonc" to "json", "kt" to "kotlin",
        "kts" to "kotlin", "lua" to "lua", "md" to "markdown", "mdx" to "markdown",
        "m" to "objective-c", "mm" to "objective-c", "php" to "php", "plist" to "xml",
        "properties" to "properties", "py" to "python", "rb" to "ruby", "rs" to "rust",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "sql" to "sql",
        "swift" to "swift", "toml" to "toml", "ts" to "typescript", "tsx" to "typescript",
        "txt" to "plaintext", "vue" to "html", "xml" to "xml", "yaml" to "yaml", "yml" to "yaml"
    )

    private val MEDIA_TYPE_LANGUAGES = mapOf(
        "application/json" to "json",
        "application/javascript" to "javascript",
        "application/sql" to "sql",
        "application/xml" to "xml",
        "application/x-httpd-php" to "php",
        "application/x-sh" to "shell",
        "application/x-yaml" to "yaml",
        "text/css" to "css",
        "text/html" to "html",
        "text/javascript" to "javascript",
        "text/markdown" to "markdown",
        "text/plain" to "plaintext",
        "text/x-c" to "c",
        "text/x-c++" to "cpp",
        "text/x-java-source" to "java",
        "text/x-kotlin" to "kotlin",
        "text/x-python" to "python",
        "text/x-swift" to "swift",
        "text/xml" to "xml",
        "text/yaml" to "yaml"
    )

    private val LANGUAGE_DISPLAY_NAMES = mapOf(
        "c" to "C", "cpp" to "C++", "csharp" to "C#", "css" to "CSS", "dart" to "Dart",
        "go" to "Go", "groovy" to "Groovy", "html" to "HTML", "java" to "Java",
        "javascript" to "JavaScript", "json" to "JSON", "kotlin" to "Kotlin", "lua" to "Lua",
        "markdown" to "Markdown", "objective-c" to "Objective-C", "php" to "PHP",
        "plaintext" to "Text", "properties" to "Properties", "python" to "Python",
        "ruby" to "Ruby", "rust" to "Rust", "shell" to "Shell", "sql" to "SQL",
        "swift" to "Swift", "toml" to "TOML", "typescript" to "TypeScript", "xml" to "XML",
        "yaml" to "YAML"
    )

    private val HASH_COMMENT_LANGUAGES = setOf(
        "python", "ruby", "shell", "yaml", "toml", "properties", "php"
    )
    private val MARKUP_LANGUAGES = setOf("html", "xml")
    private val BLOCK_COMMENT_LANGUAGES = setOf(
        "c", "cpp", "csharp", "css", "dart", "go", "groovy", "java", "javascript", "json",
        "kotlin", "objective-c", "php", "rust", "sql", "swift", "typescript"
    )
    private val LINE_COMMENT_LANGUAGES = BLOCK_COMMENT_LANGUAGES - setOf("css", "sql")
    private val CONFIG_KEY_PATTERN = Regex("(?m)^[ \\t-]*([A-Za-z_][A-Za-z0-9_.-]*)(?=[ \\t]*[:=])")
    private val SHELL_VARIABLE_PATTERN = Regex("\\$(?:\\{[A-Za-z_][A-Za-z0-9_]*\\}|[A-Za-z_][A-Za-z0-9_]*|[0-9@?#])")

    private val CSS_PROPERTY_PATTERN = Regex("(?m)^[ \\t]*([A-Za-z-]+)(?=\\s*:)")
    private val CSS_SELECTOR_PATTERN = Regex("(?m)(?:^|[},])\\s*([.#]?[A-Za-z][A-Za-z0-9_-]*)(?=\\s*[{,])")
    private val TYPE_PATTERN = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")

    private val LANGUAGE_KEYWORDS = mapOf(
        "python" to setOf(
            "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
            "del", "elif", "else", "except", "False", "finally", "for", "from", "global", "if",
            "import", "in", "is", "lambda", "match", "None", "nonlocal", "not", "or", "pass",
            "raise", "return", "self", "True", "try", "while", "with", "yield"
        ),
        "kotlin" to setOf(
            "as", "break", "class", "continue", "data", "do", "else", "false", "for", "fun", "if",
            "in", "interface", "internal", "is", "null", "object", "open", "override", "private",
            "protected", "public", "return", "sealed", "suspend", "this", "throw", "true", "try",
            "typealias", "val", "var", "when", "while"
        ),
        "java" to setOf(
            "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "false", "final",
            "finally", "float", "for", "if", "implements", "import", "instanceof", "int", "interface",
            "long", "native", "new", "null", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "true", "try", "void", "volatile", "while"
        ),
        "swift" to setOf(
            "actor", "as", "associatedtype", "async", "await", "break", "case", "catch", "class",
            "continue", "default", "defer", "do", "else", "enum", "extension", "false", "for", "func",
            "guard", "if", "import", "in", "init", "inout", "internal", "is", "let", "nil", "open",
            "private", "protocol", "public", "repeat", "return", "self", "some", "static", "struct",
            "switch", "throw", "throws", "true", "try", "typealias", "var", "where", "while"
        ),
        "javascript" to JAVASCRIPT_KEYWORDS,
        "typescript" to JAVASCRIPT_KEYWORDS + setOf(
            "abstract", "any", "as", "boolean", "declare", "enum", "implements", "interface", "keyof",
            "namespace", "never", "number", "readonly", "string", "type", "unknown"
        ),
        "c" to C_KEYWORDS,
        "cpp" to C_KEYWORDS + setOf(
            "alignas", "alignof", "auto", "bool", "catch", "class", "constexpr", "delete", "explicit",
            "friend", "namespace", "new", "noexcept", "nullptr", "operator", "private", "protected",
            "public", "template", "this", "throw", "try", "typename", "using", "virtual"
        ),
        "csharp" to C_KEYWORDS + setOf(
            "async", "await", "base", "bool", "decimal", "delegate", "event", "get", "interface",
            "internal", "is", "lock", "namespace", "new", "null", "object", "override", "partial",
            "private", "protected", "public", "record", "set", "string", "using", "var", "virtual"
        ),
        "go" to setOf(
            "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
            "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
            "return", "select", "struct", "switch", "type", "var"
        ),
        "rust" to setOf(
            "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
            "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move",
            "mut", "pub", "ref", "return", "self", "static", "struct", "super", "trait", "true", "type",
            "unsafe", "use", "where", "while"
        ),
        "ruby" to setOf(
            "alias", "and", "begin", "break", "case", "class", "def", "defined", "do", "else", "elsif",
            "end", "ensure", "false", "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
            "rescue", "retry", "return", "self", "super", "then", "true", "undef", "unless", "until",
            "when", "while", "yield"
        ),
        "php" to setOf(
            "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone",
            "const", "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "endfor",
            "endforeach", "endif", "endswitch", "endwhile", "extends", "false", "final", "finally", "fn",
            "for", "foreach", "function", "global", "if", "implements", "include", "instanceof", "interface",
            "namespace", "new", "null", "private", "protected", "public", "require", "return", "static",
            "switch", "throw", "trait", "true", "try", "use", "var", "while"
        ),
        "sql" to setOf(
            "ALTER", "AND", "AS", "ASC", "BEGIN", "BETWEEN", "BY", "CASE", "CREATE", "DELETE", "DESC",
            "DISTINCT", "DROP", "ELSE", "END", "EXISTS", "FROM", "GROUP", "HAVING", "IN", "INDEX",
            "INSERT", "INTO", "IS", "JOIN", "LIMIT", "NOT", "NULL", "ON", "OR", "ORDER", "OUTER",
            "PRIMARY", "SELECT", "SET", "TABLE", "THEN", "UNION", "UNIQUE", "UPDATE", "VALUES", "WHEN",
            "WHERE", "WITH"
        ),
        "dart" to setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class", "const",
            "continue", "default", "deferred", "do", "dynamic", "else", "enum", "export", "extends",
            "extension", "external", "factory", "false", "final", "finally", "for", "get", "if",
            "implements", "import", "in", "interface", "is", "late", "library", "mixin", "new", "null",
            "on", "operator", "part", "required", "return", "set", "static", "super", "switch", "sync",
            "this", "throw", "true", "try", "typedef", "var", "void", "while", "with", "yield"
        ),
        "lua" to setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
            "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while"
        ),
        "shell" to setOf(
            "case", "do", "done", "elif", "else", "esac", "fi", "for", "function", "if", "in", "select",
            "then", "time", "until", "while", "export", "local", "echo", "printf", "source", "set", "unset"
        )
    )

    private val JAVASCRIPT_KEYWORDS: Set<String>
        get() = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default",
        "delete", "do", "else", "export", "extends", "false", "finally", "for", "from", "function", "get",
        "if", "import", "in", "instanceof", "let", "new", "null", "of", "return", "set", "static", "super",
        "switch", "this", "throw", "true", "try", "typeof", "undefined", "var", "void", "while", "with",
        "yield"
    )
    private val C_KEYWORDS: Set<String>
        get() = setOf(
        "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern",
        "float", "for", "if", "inline", "int", "long", "register", "restrict", "return", "short", "signed",
        "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while"
    )
}
