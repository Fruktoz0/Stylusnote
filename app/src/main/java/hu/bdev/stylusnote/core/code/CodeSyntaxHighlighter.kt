package hu.bdev.stylusnote.core.code

/**
 * Supported languages for embedded [hu.bdev.stylusnote.domain.model.PageObject.CodeBlockObject] zones.
 */
enum class CodeLanguage(
    val displayName: String,
    val badge: String
) {
    SQL("SQL", "SQL"),
    PYTHON("Python", "PY"),
    JS_TS("JS / TS", "JS/TS"),
    CSHARP("C#", "C#");

    companion object {
        fun fromNameOrNull(raw: String?): CodeLanguage? =
            entries.firstOrNull { it.name == raw }
    }
}

enum class CodeTokenType {
    PLAIN,
    KEYWORD,
    TYPE_OR_BUILTIN,
    STRING,
    NUMBER,
    COMMENT,
    OPERATOR
}

data class CodeToken(
    val text: String,
    val type: CodeTokenType
)

/**
 * Lightweight, allocation-conscious, pure-Kotlin tokenizer & theme palette for syntax-highlighted
 * embedded code blocks across Canvas, PDF export, and PageThumbnails.
 */
object CodeSyntaxHighlighter {

    private val sqlKeywords = setOf(
        "select", "from", "where", "join", "inner", "left", "right", "full", "outer", "on", "as",
        "group", "by", "order", "having", "limit", "offset", "insert", "into", "values", "update",
        "set", "delete", "create", "alter", "drop", "table", "index", "view", "distinct", "union",
        "all", "and", "or", "not", "in", "is", "null", "true", "false", "case", "when", "then",
        "else", "end", "exists", "between", "like", "with", "asc", "desc", "primary", "key",
        "foreign", "references", "default", "returning"
    )
    private val sqlTypes = setOf(
        "int", "integer", "bigint", "smallint", "text", "varchar", "char", "boolean", "bool",
        "real", "float", "double", "numeric", "decimal", "date", "timestamp", "uuid", "json",
        "jsonb", "blob", "count", "sum", "avg", "min", "max", "coalesce"
    )

    private val pythonKeywords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield", "match", "case"
    )
    private val pythonTypes = setOf(
        "int", "float", "str", "bool", "list", "dict", "set", "tuple", "bytes", "object",
        "print", "len", "range", "enumerate", "zip", "map", "filter", "sum", "min", "max", "any",
        "all", "sorted", "isinstance", "type", "self", "cls"
    )

    private val jsTsKeywords = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
        "do", "else", "enum", "export", "extends", "false", "finally", "for", "function", "if",
        "implements", "import", "in", "instanceof", "interface", "let", "new", "null", "of",
        "private", "protected", "public", "readonly", "return", "static", "super", "switch",
        "this", "throw", "true", "try", "type", "typeof", "undefined", "var", "void", "while",
        "yield", "async", "await", "as", "from", "keyof"
    )
    private val jsTsTypes = setOf(
        "string", "number", "boolean", "unknown", "never", "any", "object", "Record", "Array",
        "Promise", "Map", "Set", "Partial", "Pick", "Omit", "console", "Math", "JSON", "Date"
    )

    private val csharpKeywords = setOf(
        "abstract", "as", "async", "await", "base", "break", "case", "catch", "class", "const",
        "continue", "default", "delegate", "do", "else", "enum", "event", "explicit", "extern",
        "false", "finally", "fixed", "for", "foreach", "goto", "if", "implicit", "in",
        "interface", "internal", "is", "lock", "namespace", "new", "null", "operator", "out",
        "override", "params", "private", "protected", "public", "readonly", "record", "ref",
        "return", "sealed", "sizeof", "stackalloc", "static", "struct", "switch", "this", "throw",
        "true", "try", "typeof", "using", "var", "virtual", "volatile", "while", "yield", "get",
        "set", "init", "required"
    )
    private val csharpTypes = setOf(
        "bool", "byte", "char", "decimal", "double", "float", "int", "long", "object", "sbyte",
        "short", "string", "uint", "ulong", "ushort", "void", "dynamic", "List", "Dictionary",
        "IEnumerable", "Task", "ValueTask", "CancellationToken", "Console", "Math", "String"
    )

    fun tokenizeDocument(code: String, language: CodeLanguage): List<List<CodeToken>> {
        if (code.isEmpty()) return listOf(emptyList())
        val rawLines = code.split('\n')
        val out = ArrayList<List<CodeToken>>(rawLines.size)
        var inBlockComment = false
        for (i in 0 until rawLines.size) {
            val line = rawLines[i].trimEnd('\r')
            val (tokens, nextBlockComment) = tokenizeLine(line, language, inBlockComment)
            out.add(tokens)
            inBlockComment = nextBlockComment
        }
        return out
    }

    fun tokenizeLine(
        line: String,
        language: CodeLanguage,
        inBlockComment: Boolean = false
    ): Pair<List<CodeToken>, Boolean> {
        if (line.isEmpty()) return emptyList<CodeToken>() to inBlockComment

        val tokens = ArrayList<CodeToken>(8)
        val n = line.length
        var i = 0
        var blockComment = inBlockComment

        while (i < n) {
            if (blockComment) {
                val closeIdx = line.indexOf("*/", startIndex = i)
                if (closeIdx < 0) {
                    tokens.add(CodeToken(line.substring(i), CodeTokenType.COMMENT))
                    return tokens to true
                }
                tokens.add(CodeToken(line.substring(i, closeIdx + 2), CodeTokenType.COMMENT))
                i = closeIdx + 2
                blockComment = false
                continue
            }

            val c = line[i]
            // Whitespace run
            if (c.isWhitespace()) {
                val start = i
                while (i < n && line[i].isWhitespace()) i++
                tokens.add(CodeToken(line.substring(start, i), CodeTokenType.PLAIN))
                continue
            }

            // Line & block comments by language
            if (language == CodeLanguage.PYTHON && c == '#') {
                tokens.add(CodeToken(line.substring(i), CodeTokenType.COMMENT))
                return tokens to false
            }
            if (language == CodeLanguage.SQL && c == '-' && i + 1 < n && line[i + 1] == '-') {
                tokens.add(CodeToken(line.substring(i), CodeTokenType.COMMENT))
                return tokens to false
            }
            if ((language == CodeLanguage.JS_TS || language == CodeLanguage.CSHARP) &&
                c == '/' && i + 1 < n && line[i + 1] == '/'
            ) {
                tokens.add(CodeToken(line.substring(i), CodeTokenType.COMMENT))
                return tokens to false
            }
            if (language != CodeLanguage.PYTHON && c == '/' && i + 1 < n && line[i + 1] == '*') {
                val closeIdx = line.indexOf("*/", startIndex = i + 2)
                if (closeIdx < 0) {
                    tokens.add(CodeToken(line.substring(i), CodeTokenType.COMMENT))
                    return tokens to true
                }
                tokens.add(CodeToken(line.substring(i, closeIdx + 2), CodeTokenType.COMMENT))
                i = closeIdx + 2
                continue
            }

            // Strings ('...', "...", `...`)
            val isStringDelimiter = c == '\'' || c == '"' || (language == CodeLanguage.JS_TS && c == '`')
            if (isStringDelimiter) {
                val quote = c
                val start = i
                i++
                while (i < n) {
                    val ch = line[i]
                    if (ch == '\\' && language != CodeLanguage.SQL && i + 1 < n) {
                        i += 2
                        continue
                    }
                    if (ch == quote) {
                        i++
                        if (language == CodeLanguage.SQL && i < n && line[i] == quote) {
                            i++
                            continue
                        }
                        break
                    }
                    i++
                }
                tokens.add(CodeToken(line.substring(start, i), CodeTokenType.STRING))
                continue
            }

            // Numbers (hex / int / float)
            if (c.isDigit()) {
                val start = i
                if (c == '0' && i + 1 < n && (line[i + 1] == 'x' || line[i + 1] == 'X')) {
                    i += 2
                    while (i < n && (line[i].isDigit() || line[i] in 'a'..'f' || line[i] in 'A'..'F' || line[i] == '_')) {
                        i++
                    }
                } else {
                    while (i < n && (line[i].isDigit() || line[i] == '.' || line[i] == '_')) i++
                    if (i < n && (line[i] == 'e' || line[i] == 'E') && i + 1 < n &&
                        (line[i + 1].isDigit() || line[i + 1] == '+' || line[i + 1] == '-')
                    ) {
                        i += 2
                        while (i < n && line[i].isDigit()) i++
                    }
                }
                tokens.add(CodeToken(line.substring(start, i), CodeTokenType.NUMBER))
                continue
            }

            // Identifiers / Keywords
            if (c.isLetter() || c == '_' || c == '@') {
                val start = i
                i++
                while (i < n && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                val word = line.substring(start, i)
                val type = classifyWord(word, language)
                tokens.add(CodeToken(word, type))
                continue
            }

            // Operators / punctuation
            val start = i
            i++
            if (i < n && ((c == '=' && line[i] == '>') ||
                    (c == '-' && line[i] == '>') ||
                    (c == '=' && line[i] == '=') ||
                    (c == '!' && line[i] == '=') ||
                    (c == '<' && line[i] == '=') ||
                    (c == '>' && line[i] == '='))
            ) {
                i++
            }
            tokens.add(CodeToken(line.substring(start, i), CodeTokenType.OPERATOR))
        }

        return tokens to blockComment
    }

    private fun classifyWord(word: String, language: CodeLanguage): CodeTokenType = when (language) {
        CodeLanguage.SQL -> {
            val lower = word.lowercase()
            when {
                lower in sqlKeywords -> CodeTokenType.KEYWORD
                lower in sqlTypes -> CodeTokenType.TYPE_OR_BUILTIN
                else -> CodeTokenType.PLAIN
            }
        }
        CodeLanguage.PYTHON -> when {
            word in pythonKeywords -> CodeTokenType.KEYWORD
            word in pythonTypes -> CodeTokenType.TYPE_OR_BUILTIN
            else -> CodeTokenType.PLAIN
        }
        CodeLanguage.JS_TS -> when {
            word in jsTsKeywords -> CodeTokenType.KEYWORD
            word in jsTsTypes -> CodeTokenType.TYPE_OR_BUILTIN
            else -> CodeTokenType.PLAIN
        }
        CodeLanguage.CSHARP -> when {
            word in csharpKeywords -> CodeTokenType.KEYWORD
            word in csharpTypes -> CodeTokenType.TYPE_OR_BUILTIN
            else -> CodeTokenType.PLAIN
        }
    }

    fun tokenColorArgb(type: CodeTokenType, isOledMode: Boolean): Int = if (isOledMode) {
        when (type) {
            CodeTokenType.PLAIN -> 0xFFE2E8F0.toInt()
            CodeTokenType.KEYWORD -> 0xFFC084FC.toInt()
            CodeTokenType.TYPE_OR_BUILTIN -> 0xFF38BDF8.toInt()
            CodeTokenType.STRING -> 0xFF4ADE80.toInt()
            CodeTokenType.NUMBER -> 0xFFFB923C.toInt()
            CodeTokenType.COMMENT -> 0xFF64748B.toInt()
            CodeTokenType.OPERATOR -> 0xFF94A3B8.toInt()
        }
    } else {
        when (type) {
            CodeTokenType.PLAIN -> 0xFF0F172A.toInt()
            CodeTokenType.KEYWORD -> 0xFF7C3AED.toInt()
            CodeTokenType.TYPE_OR_BUILTIN -> 0xFF0284C7.toInt()
            CodeTokenType.STRING -> 0xFF15803D.toInt()
            CodeTokenType.NUMBER -> 0xFFC2410C.toInt()
            CodeTokenType.COMMENT -> 0xFF64748B.toInt()
            CodeTokenType.OPERATOR -> 0xFF475569.toInt()
        }
    }

    fun cardBackgroundArgb(isOledMode: Boolean): Int =
        if (isOledMode) 0xFF111622.toInt() else 0xFFF8FAFC.toInt()

    fun cardHeaderArgb(isOledMode: Boolean): Int =
        if (isOledMode) 0xFF192234.toInt() else 0xFFEFF4FA.toInt()

    fun cardBorderArgb(isOledMode: Boolean): Int =
        if (isOledMode) 0xFF2D3A52.toInt() else 0xFFCBD5E1.toInt()

    fun gutterTextArgb(isOledMode: Boolean): Int =
        if (isOledMode) 0xFF64748B.toInt() else 0xFF94A3B8.toInt()

    fun badgeBackgroundArgb(isOledMode: Boolean): Int =
        if (isOledMode) 0xFF1D4ED8.toInt() else 0xFFDBEAFE.toInt()

    fun badgeTextArgb(isOledMode: Boolean): Int =
        if (isOledMode) 0xFFDBEAFE.toInt() else 0xFF1D4ED8.toInt()
}
