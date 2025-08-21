package com.supernova.testgate.audits

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/** Per-tool whitelist matcher. Accepts FQCNs and wildcards like `.*` and `..*`. */
class WhitelistMatcher(patterns: List<String>) {
    private val regexes: List<Regex> = patterns
        .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        .map { token ->
            var p = token.replace('.', '/')
            p = p.replace(Regex("/+"), "/")
            p = p.replace("..*", "/**")
            p = p.replace(".*", "/*")
            val escaped = Regex.escape(p)
                .replace("\\/\\*\\*", "(?:/.*)?") // /** → any depth (including empty)
                .replace("\\/\\*", "/[^/]*")       // /*  → single segment
                .replace("\\*", "[^/]*")             // *   → segment chars
            ("^(.*/)?$escaped$").toRegex()
        }

    fun matchesFqcnOrSymbol(value: String?): Boolean =
        value?.let { v -> regexes.any { it.matches(normalize(v)) } } ?: false

    fun matchesPath(path: String?): Boolean =
        path?.let { p -> regexes.any { it.matches(normalizePath(p)) } } ?: false

    private fun normalize(v: String): String = v.replace('.', '/').trim()
    private fun normalizePath(p: String): String = p.replace('\\', '/').trim()
}

// -------- XML + FS helpers shared by audits --------

internal fun xml(file: File): Document {
    val dbf = DocumentBuilderFactory.newInstance()
    dbf.isNamespaceAware = false
    val db = dbf.newDocumentBuilder()
    file.inputStream().use { return db.parse(it) }
}

internal fun scanSourceFiles(moduleDir: File): Int {
    val root = moduleDir.toPath()
    val patterns = listOf(
        root.resolve("src").resolve("main"),
        root.resolve("src").resolve("debug"),
        root.resolve("src").resolve("release"),
        root.resolve("src").resolve("test"),
        root.resolve("src").resolve("androidTest")
    )
    var count = 0
    patterns.filter { Files.exists(it) }.forEach { base ->
        Files.walk(base).use { stream ->
            count += stream.filter { p ->
                val name = p.fileName?.toString()?.lowercase() ?: return@filter false
                name.endsWith(".kt") || name.endsWith(".java")
            }.count().toInt()
        }
    }
    return count
}

fun deriveFqcnFromPath(filePath: String?): String? {
    if (filePath == null) return null
    val unix = filePath.replace('\\', '/')
    val m = Regex("/src/(?:test|androidTest|main|debug|release)/(?:java|kotlin)/(.+)\\.(kt|java)$").find(unix)
    val core = m?.groupValues?.get(1) ?: return null
    return core.replace('/', '.')
}

fun extractRuleIdFromMessage(message: String): String {
    val m = Regex("^\\[([^]]+)\\]").find(message)
    if (m != null) return m.groupValues[1]
    return when {
        message.contains("ForbiddenImport", true) -> "ForbiddenImport"
        message.contains("ForbiddenMethodCall", true) -> "ForbiddenMethodCall"
        message.contains("RequireHarnessAnnotationOnTests", true) -> "RequireHarnessAnnotationOnTests"
        else -> "Unknown"
    }
}

fun extractSymbolFromMessage(message: String): String? =
    Regex("'([A-Za-z0-9_.$]+)'").find(message)?.groupValues?.get(1)
