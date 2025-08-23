package com.supernova.testgate.audits

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * Matches file paths or FQCNs against whitelist patterns.
 *
 * Supported wildcards (path-glob style):
 *  - `*`  : any chars within a single segment (no '/')
 *  - `**` : any depth across directories (including zero segments)
 *  - `?`  : any single char within a segment
 *
 * Legacy FQCN-style wildcards are also supported:
 *  - `.*`  : single segment
 *  - `..*` : any depth (including zero)
 *
 * Notes:
 *  - Paths are normalized to forward slashes.
 *  - If a pattern starts with '/', it’s treated as anchored to the start of the path.
 *    Otherwise it can match as a suffix (i.e., anywhere in the path).
 */
class WhitelistMatcher(patterns: Collection<String>) {

    private val regexes: List<Regex> = patterns
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { compilePatternVariants(it) }
        .toList()

    /**
     * Checks if a file system path matches any whitelist pattern.
     * @param path A file path; Windows backslashes are accepted.
     */
    fun matchesPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val norm = normalizePath(path)
        return regexes.any { it.matches(norm) }
    }

    /**
     * Checks if a fully-qualified class/symbol matches any whitelist pattern.
     * Matches both dot-form (e.g., com.foo.Bar) and slash-form.
     */
    fun matchesFqcnOrSymbol(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val dot = value
        val slash = value.replace('.', '/')
        val normSlash = normalizePath(slash)
        return regexes.any { rx -> rx.matches(dot) || rx.matches(normSlash) }
    }

    // --- internals ---

    private fun normalizePath(p: String): String {
        val s = p.replace('\\', '/')
        // ensure leading slash so our anchored regexes are consistent
        return if (s.startsWith('/')) s else "/$s"
    }

    private fun compilePatternVariants(raw: String): Sequence<Regex> {
        // Normalize path separators in the raw pattern
        val pat = raw.replace('\\', '/')

        val variants = mutableListOf<Pair<String, Boolean>>() // (pattern, anchored?)
        val anchored = pat.startsWith('/')

        // Variant 1: treat the pattern as path-style as-is
        variants += pat to anchored

        // Variant 2: if it looks like an FQCN (no slashes but contains dots), support legacy wildcards
        val looksLikeFqcn = !pat.contains('/') && pat.contains('.')
        if (looksLikeFqcn) {
            var fq = pat
            // legacy wildcards: "..*" => "/**", ".*" => "/*", then convert dots to slashes
            fq = fq.replace("..*", "/**")
            fq = fq.replace(".*", "/*")
            fq = fq.replace('.', '/')
            variants += (fq to false) // not anchored by default
        }

        return variants.asSequence().map { (v, isAnchored) -> globToRegex(v, isAnchored) }
    }

    private fun globToRegex(globIn: String, anchored: Boolean): Regex {
        // Remove a single leading slash; we'll add our own anchors
        val glob = if (globIn.startsWith('/')) globIn.drop(1) else globIn

        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    val isDouble = i + 1 < glob.length && glob[i + 1] == '*'
                    if (isDouble) {
                        // consume the second '*'
                        i += 2
                        // swallow one optional following slash for nicer authoring of "**/foo"
                        if (i < glob.length && glob[i] == '/') i += 1
                        // '**' => any depth (including empty), across directories
                        sb.append(".*")
                    } else {
                        // '*' => any chars except '/'
                        sb.append("[^/]*")
                        i += 1
                    }
                }
                '?' -> { sb.append("[^/]"); i += 1 }
                else -> { sb.append(Regex.escape(c.toString())); i += 1 }
            }
        }

        // Build final regex:
        // - We always match against a normalized path that starts with '/'.
        // - If pattern is anchored (started with '/'), require it from the start.
        // - Otherwise allow arbitrary leading directories (suffix match).
        val prefix = if (anchored) "^/" else "^/(?:.*?/)?"
        val pattern = "$prefix$sb$"
        return Regex(pattern)
    }
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