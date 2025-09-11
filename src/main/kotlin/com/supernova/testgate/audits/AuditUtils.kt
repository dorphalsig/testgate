package com.supernova.testgate.audits

import org.w3c.dom.Document
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Utility classes and helpers shared by TestGate audits.
 *
 * Keep helpers small and practical—no micro abstractions.
 */

// -------------------- Whitelist matching --------------------

/**
 * Matches file paths or FQCNs/symbols against allow-list patterns.
 *
 * Pattern semantics:
 * - Path-style wildcards:
 *   *  : any chars within a single segment (no '/')
 *   ** : any depth across directories (including zero segments)
 *   ?  : any single char within a segment
 * - Leading "/" in a pattern anchors it to the start of the (normalized) path.
 *   Without leading "/", the pattern may match anywhere in the path.
 * - FQCNs are matched with dot-form as-is and slash-form ('.' -> '/').
 */
class WhitelistMatcher(patterns: Collection<String>) {
    private val compiled: List<Regex> = patterns.filter { it.isNotBlank() }.map { toRegex(it.trim()) }

    fun matchesPath(rawPath: String?): Boolean {
        if (rawPath.isNullOrBlank()) return false
        val p = normalizePath(rawPath)
        return compiled.any { it.matches(p) }
    }

    fun matchesFqcnOrSymbol(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val dot = value
        val slash = value.replace('.', '/')
        val norm = normalizePath(slash)
        return compiled.any { rx -> rx.matches(dot) || rx.matches(norm) }
    }

    private fun normalizePath(p: String): String {
        val s = p.replace('\\', '/')
        // Ensure a single leading "/" for anchored patterns to behave consistently
        return if (s.startsWith('/')) s else "/$s"
    }

    private fun toRegex(glob: String): Regex {
        val anchored = glob.startsWith('/')
        val body = glob.replace('\\', '/').let { g ->
            val esc = StringBuilder()
            var i = 0
            while (i < g.length) {
                val c = g[i]
                if (c == '*') {
                    val isDouble = (i + 1 < g.length && g[i + 1] == '*')
                    if (isDouble) {
                        esc.append(".*")
                        i += 2
                    } else {
                        esc.append("[^/]*")
                        i += 1
                    }
                    continue
                }
                if (c == '?') {
                    esc.append("[^/]"); i += 1; continue
                }
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) esc.append('\\')
                esc.append(c)
                i += 1
            }
            esc.toString()
        }
        val prefix = if (anchored) "^" else ".*?"
        val suffix = "$"
        return Regex(prefix + body + suffix)
    }
}

// -------------------- XML parsing --------------------

/**
 * Parse an XML file into a DOM Document with secure defaults.
 * Throws the underlying exception to let audits fail fast on real errors.
 */
internal fun xml(file: File): Document {
    val dbf = DocumentBuilderFactory.newInstance().apply {
        try {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        } catch (_: Exception) {
            // ignore if not supported
        }
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    file.inputStream().use { ins ->
        return dbf.newDocumentBuilder().parse(ins)
    }
}

// -------------------- Source scanning --------------------

/**
 * Count Kotlin/Java source files under src/ ** of the given module.
 * Used for tolerance math in Detekt/Lint audits.
 */
internal fun scanSourceFiles(moduleDir: File): Int {
    val src = File(moduleDir, "src")
    if (!src.exists()) return 0
    var count = 0
    src.walkTopDown().forEach { f ->
        if (f.isFile) {
            val n = f.name.lowercase()
            if (n.endsWith(".kt") || n.endsWith(".java")) count++
        }
    }
    return count
}

// -------------------- Kotlin/Java header parsing --------------------

/**
 * Parsed header information from a Kotlin/Java source file.
 *
 * @param pkg package name if declared
 * @param pkgLine line number (1-based) of the package declaration
 * @param imports set of fully-qualified imports (as written)
 * @param classDecls list of top-level class/object/interface names with their line numbers (1-based)
 */
data class Header(
    val pkg: String?,
    val pkgLine: Int?,
    val imports: Set<String>,
    val classDecls: List<Pair<String, Int>>
)

/**
 * Fast, line-based header reader—no full parsing. Safe for both Kotlin and Java.
 * Note: avoids control-flow (continue/break) from inside lambdas.
 */
internal fun readHeader(file: File): Header {
    val lines = file.readLines()
    var pkg: String? = null
    var pkgLine: Int? = null
    val imports = linkedSetOf<String>()
    val decls = mutableListOf<Pair<String, Int>>()

    val rePkg = Regex("""^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
    val reImport = Regex("""^\s*import\s+([A-Za-z_][A-Za-z0-9_.]*(?:\.\*)?)""")
    val reDecl = Regex("""^\s*(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)""")

    for ((idx, rawLine) in lines.withIndex()) {
        val lineNo = idx + 1
        val line = rawLine.trim()

        if (pkg == null) {
            val m = rePkg.find(line)
            if (m != null) {
                pkg = m.groupValues[1]
                pkgLine = lineNo
                continue
            }
        }

        val mImport = reImport.find(line)
        if (mImport != null) {
            imports += mImport.groupValues[1]
            continue
        }

        val mDecl = reDecl.find(line)
        if (mDecl != null) {
            decls += mDecl.groupValues[1] to lineNo
            continue
        }

        // Stop early if we passed typical header region (heuristic)
        if (lineNo > 400) break
    }
    return Header(pkg, pkgLine, imports, decls)
}

// -------------------- Path normalization helpers --------------------

/**
 * Return a module-relative, normalized path (forward slashes, no leading slash).
 * Falls back to a normalized input if the file cannot be relativized.
 */
fun moduleRelativePath(moduleDir: File, file: File): String {
    return try {
        val base = moduleDir.canonicalFile
        val target = if (file.isAbsolute) file.canonicalFile else File(base, file.path).canonicalFile
        val basePath = base.path.replace('\\', '/')
        val targetPath = target.path.replace('\\', '/')
        if (targetPath.startsWith(basePath)) {
            targetPath.substring(basePath.length).trimStart('/')
        } else {
            targetPath.trimStart('/')
        }
    } catch (_: Throwable) {
        file.path.replace('\\', '/').trimStart('/')
    }
}

/** Overload for raw strings. */
fun moduleRelativePath(moduleDir: File, path: String): String = moduleRelativePath(moduleDir, File(path))
