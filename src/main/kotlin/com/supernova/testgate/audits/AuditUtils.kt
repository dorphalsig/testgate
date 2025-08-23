package com.supernova.testgate.audits

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * Per-tool whitelist matcher. Accepts FQCNs and wildcards like `.*` and `..*`.
 *
 * Converts dot-separated patterns to regex patterns that can match against
 * normalized paths and fully qualified class names.
 *
 * @param patterns List of whitelist patterns supporting wildcards:
 *   - `.*` matches single path segment
 *   - `..*` matches any depth including empty
 *   - Regular FQCNs match exactly
 */
class WhitelistMatcher(patterns: Collection<String>) {
    private val regexes: List<Regex> = patterns
        .mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
        .map { token ->
            var p = token.replace('.', '/')
            p = p.replace(Regex("/+"), "/")
            p = p.replace("..*", "/**")
            p = p.replace(".*", "/*")
            val escaped = Regex.escape(p)
                .replace("\\/\\*\\*", "(?:/.*)?") // /** â†' any depth (including empty)
                .replace("\\/\\*", "/[^/]*")       // /*  â†' single segment
                .replace("\\*", "[^/]*")             // *   â†' segment chars
            ("^(.*/)?$escaped$").toRegex()
        }

    /**
     * Checks if a fully qualified class name or symbol matches any whitelist pattern.
     *
     * @param value The FQCN or symbol to check (dots will be normalized to slashes)
     * @return true if the value matches any pattern, false otherwise or if value is null
     */
    fun matchesFqcnOrSymbol(value: String?): Boolean =
        value?.let { v -> regexes.any { it.matches(normalize(v)) } } ?: false

    /**
     * Checks if a file path matches any whitelist pattern.
     *
     * @param path The file path to check (backslashes will be normalized to forward slashes)
     * @return true if the path matches any pattern, false otherwise or if path is null
     */
    fun matchesPath(path: String?): Boolean =
        path?.let { p -> regexes.any { it.matches(normalizePath(p)) } } ?: false

    private fun normalize(v: String): String = v.replace('.', '/').trim()
    private fun normalizePath(p: String): String = p.replace('\\', '/').trim()
}

// -------- XML + FS helpers shared by audits --------

/**
 * Parses an XML file and returns a DOM Document.
 *
 * @param file The XML file to parse
 * @return Parsed XML Document
 * @throws Exception if file cannot be read or parsed
 */
internal fun xml(file: File): Document {
    val dbf = DocumentBuilderFactory.newInstance()
    dbf.isNamespaceAware = false
    val db = dbf.newDocumentBuilder()
    file.inputStream().use { return db.parse(it) }
}

/**
 * Scans and counts Kotlin and Java source files in standard Android module directories.
 *
 * Searches in: src/main, src/debug, src/release, src/test, src/androidTest
 *
 * @param moduleDir The module root directory containing src/ folder
 * @return Total count of .kt and .java files found
 */
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

/**
 * Extracts a rule ID from an audit message.
 *
 * First attempts to parse a bracketed rule ID from the start of the message.
 * Falls back to detecting known rule types by content matching.
 *
 * @param message The audit message to parse
 * @return The extracted rule ID or "Unknown" if no rule can be identified
 */
fun extractRuleIdFromMessage(message: String): String {
    val m = Regex("^\\[([^]]+)]").find(message)
    if (m != null) return m.groupValues[1]
    return when {
        message.contains("ForbiddenImport", true) -> "ForbiddenImport"
        message.contains("ForbiddenMethodCall", true) -> "ForbiddenMethodCall"
        message.contains("RequireHarnessAnnotationOnTests", true) -> "RequireHarnessAnnotationOnTests"
        else -> "Unknown"
    }
}

/**
 * Reads and parses the header section of a Kotlin or Java source file.
 *
 * Extracts package declaration, import statements, and top-level class/interface/object declarations.
 *
 * @param file The source file to parse
 * @return Header object containing parsed information
 */
fun readHeader(file: File): Header {
    val lines = file.readLines()
    var pkg: String? = null
    var pkgLine: Int? = null
    val imports = LinkedHashSet<String>()
    val decls = mutableListOf<Pair<String, Int>>()

    val pkgRe = Regex("^\\s*package\\s+([A-Za-z0-9_.]+)")
    val importRe = Regex("^\\s*import\\s+([A-Za-z0-9_.]+)")
    val ktDeclRe =
        Regex("^\\s*(?:public|internal|private|protected)?\\s*(?:data\\s+)?(?:enum\\s+)?(?:class|interface|object)\\s+([A-Za-z0-9_]+)")
    val javaDeclRe =
        Regex("^\\s*(?:public|protected|private|abstract|final|static\\s+)*\\s*(?:class|interface|enum)\\s+([A-Za-z0-9_]+)")

    lines.forEachIndexed { idx, raw ->
        val line = raw.trim()
        if (pkg == null) {
            pkgRe.find(line)?.let {
                pkg = it.groupValues[1]
                pkgLine = idx + 1
            }
        }
        importRe.find(line)?.let { imports += it.groupValues[1] }
        ktDeclRe.find(line)?.let { decls += it.groupValues[1] to (idx + 1) }
        javaDeclRe.find(line)?.let { decls += it.groupValues[1] to (idx + 1) }
    }
    return Header(pkg, pkgLine, imports, decls)
}

/**
 * Contains parsed header information from a source file.
 *
 * @property pkg The package name declared in the file, null if no package declaration
 * @property pkgLine The line number (1-based) where the package declaration was found
 * @property imports Set of imported fully qualified class names
 * @property classDecls List of top-level class/interface/object declarations with their line numbers (1-based)
 */
data class Header(
    val pkg: String?,
    val pkgLine: Int?,
    val imports: Set<String>,
    val classDecls: List<Pair<String, Int>>
)