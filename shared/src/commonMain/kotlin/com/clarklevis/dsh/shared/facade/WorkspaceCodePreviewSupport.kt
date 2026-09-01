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

    private fun tokenize(text: String, language: String): List<WorkspaceCodeToken> {
        if (text.isEmpty() || language == "plaintext") return emptyList()
        val occupied = BooleanArray(text.length)
        val tokens = mutableListOf<WorkspaceCodeToken>()

        fun addRange(start: Int, endExclusive: Int, kind: WorkspaceCodeTokenKind) {
            if (start !in text.indices || endExclusive <= start || endExclusive > text.length) return
            if ((start until endExclusive).any(occupied::get)) return
            for (index in start until endExclusive) occupied[index] = true
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

        BASE_TOKEN_PATTERN.findAll(text).forEach { match ->
            val token = match.value
            val kind = when {
                token.startsWith("//") || token.startsWith("/*") || token.startsWith("<!--") ->
                    WorkspaceCodeTokenKind.COMMENT
                token.startsWith("#") && language in HASH_COMMENT_LANGUAGES -> WorkspaceCodeTokenKind.COMMENT
                token.startsWith("--") && language == "sql" -> WorkspaceCodeTokenKind.COMMENT
                language == "json" && token.startsWith('"') &&
                    text.substring(match.range.last + 1).trimStart().startsWith(':') ->
                    WorkspaceCodeTokenKind.ATTRIBUTE
                else -> WorkspaceCodeTokenKind.STRING
            }
            val isAllowed = when {
                token.startsWith("#") -> language in HASH_COMMENT_LANGUAGES
                token.startsWith("--") -> language == "sql"
                else -> true
            }
            if (isAllowed) addRange(match.range.first, match.range.last + 1, kind)
        }

        if (language in MARKUP_LANGUAGES) {
            addGroupMatches(TAG_PATTERN, 1, WorkspaceCodeTokenKind.TAG)
            addMatches(ATTRIBUTE_PATTERN, WorkspaceCodeTokenKind.ATTRIBUTE)
        }

        if (language == "css") {
            addGroupMatches(CSS_PROPERTY_PATTERN, 1, WorkspaceCodeTokenKind.ATTRIBUTE)
            addGroupMatches(CSS_SELECTOR_PATTERN, 1, WorkspaceCodeTokenKind.TAG)
        }

        val keywords = LANGUAGE_KEYWORDS[language].orEmpty()
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
        addMatches(NUMBER_PATTERN, WorkspaceCodeTokenKind.NUMBER)

        return tokens.sortedBy(WorkspaceCodeToken::start)
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
        "python", "ruby", "shell", "yaml", "toml", "properties"
    )
    private val MARKUP_LANGUAGES = setOf("html", "xml")

    private val BASE_TOKEN_PATTERN = Regex(
        pattern = """<!--.*?-->|/\*.*?\*/|//[^\n]*|--[^\n]*|\#[^\n]*|\"\"\".*?\"\"\"|'''.*?'''|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`""",
        options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    )
    private val TAG_PATTERN = Regex("</?([A-Za-z][A-Za-z0-9:_-]*)")
    private val ATTRIBUTE_PATTERN = Regex("\\b[A-Za-z_:][A-Za-z0-9:_.-]*(?=\\s*=)")
    private val CSS_PROPERTY_PATTERN = Regex("(?m)^[ \\t]*([A-Za-z-]+)(?=\\s*:)")
    private val CSS_SELECTOR_PATTERN = Regex("(?m)(?:^|[},])\\s*([.#]?[A-Za-z][A-Za-z0-9_-]*)(?=\\s*[{,])")
    private val TYPE_PATTERN = Regex("\\b[A-Z][A-Za-z0-9_]*\\b")
    private val NUMBER_PATTERN = Regex("(?<![A-Za-z_])(?:0[xX][0-9A-Fa-f]+|\\d+(?:\\.\\d+)?)(?![A-Za-z_])")

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
            "then", "time", "until", "while"
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
