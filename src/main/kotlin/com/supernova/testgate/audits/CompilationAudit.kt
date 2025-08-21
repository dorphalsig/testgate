package com.supernova.testgate.audits

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CompilationAudit
 *
 * Capture stderr chunks via [append], then call [check] to parse and report.
 * Hook/unhook the Gradle logging listeners in task doFirst/finalizer (outside this class).
 *
 * Simplicity:
 * - No forced newlines on append (we split by real newlines during parsing).
 * - One synchronized buffer (StringBuffer) since Gradle may call listeners from different threads.
 * - No global build hooks; this class is pure audit logic + capture toggles.
 */
class CompilationAudit(
    private val module: String,
    private val moduleDir: File
) : Audit {

    private val capturing = AtomicBoolean(false)
    private val buffer = StringBuffer() // synchronized

    /** Enable capture (call from task doFirst). */
    fun registerCapture() {
        capturing.set(true)
    }

    /** Disable capture (call from finalizer before parsing). */
    fun unregisterCapture() {
        capturing.set(false)
    }

    /** Append raw stderr text chunks from Gradle's StandardErrorListener. */
    fun append(text: String) {
        if (!capturing.get()) return
        buffer.append(text)
    }

    /** Snapshot accumulated output for parsing. */
    private fun snapshot(): String = buffer.toString()

    override fun check(callback: (AuditResult) -> Unit) {
        val log = snapshot()
        val findings = parseCompilerErrors(log, moduleDir)

        // Compilation is strict: any error => FAIL (tolerance is included in result for uniformity)
        val result = AuditResult(
            module = module,
            name = "CompilationAudit",
            findings = findings,
            tolerance = 0,
            findingCount = findings.size,
            status = if (findings.isEmpty()) Status.PASS else Status.FAIL
        )
        callback(result)
    }

    // ---------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------

    private enum class Origin { KOTLIN, JAVAC, KSP, KAPT }
    private enum class ContKind { MSG_APPEND, STACK_APPEND, FLUSH }

    private data class Start(val path: String?, val line: Int?, val msg: String, val origin: Origin)
    private data class Pending(
        val path: String?,
        val line: Int?,
        val message: StringBuilder,
        val stack: MutableList<String>,
        val origin: Origin
    )

    // Patterns:
    // Kotlin (Gradle): e: /path/File.kt: (12, 8): message
    private val kotlinGradle = Regex("""^e:\s*(.+?):\s*\((\d+),\s*\d+\):\s*(.+)$""")

    // Kotlin (CLI): /path/File.kt:12:8: error: message
    private val kotlinCli = Regex("""^(.+?):(\d+):\d+:\s*error:\s*(.+)$""")

    // Javac: /path/File.java:12: error: message
    private val javac = Regex("""^(.+?):(\d+):\s*error:\s*(.+)$""")

    // KSP with path: [ksp] /path/File.kt:12:8: message  (also matches [ksp1], [ksp2], ...)
    private val kspPath = Regex("""^\[ksp(?:\d+)?]\s*(.+?):(\d+):\d+:\s*(.+)$""")

    // KSP/KAPT short msg: e: [ksp] message   or   e: [kapt] message
    private val kspShort = Regex("""^e:\s*\[(ksp(?:\d+)?|kapt)]\s*(.+)$""")

    /** Orchestrator kept small (~25 LOC), one pass over lines. */
    internal fun parseCompilerErrors(log: String, moduleDir: File): List<Finding> {
        val out = mutableListOf<Finding>()
        var pending: Pending? = null

        for (raw in log.lineSequence()) {
            val line = raw.trimEnd('\r') // normalize CRLF
            val start = tryStart(line)
            if (start != null) {
                if (pending != null) {
                    flushPending(pending, out, moduleDir)
                    pending = null
                }
                pending = newPending(start)
                continue
            }

            when (classifyContinuation(line)) {
                ContKind.FLUSH -> {
                    if (pending != null) {
                        flushPending(pending, out, moduleDir)
                        pending = null
                    }
                }

                ContKind.MSG_APPEND -> pending?.message?.append('\n')?.append(line)
                ContKind.STACK_APPEND -> pending?.stack?.add(line)
            }
        }

        if (pending != null) flushPending(pending, out, moduleDir)
        return out
    }

    private fun tryStart(line: String): Start? {
        kotlinGradle.matchEntire(line)?.let { m ->
            val (path, ln, msg) = m.destructured
            return Start(path.trim(), ln.toIntOrNull(), msg.trim(), Origin.KOTLIN)
        }
        kotlinCli.matchEntire(line)?.let { m ->
            val (path, ln, msg) = m.destructured
            return Start(path.trim(), ln.toIntOrNull(), msg.trim(), Origin.KOTLIN)
        }
        javac.matchEntire(line)?.let { m ->
            val (path, ln, msg) = m.destructured
            return Start(path.trim(), ln.toIntOrNull(), msg.trim(), Origin.JAVAC)
        }
        kspPath.matchEntire(line)?.let { m ->
            val (path, ln, msg) = m.destructured
            return Start(path.trim(), ln.toIntOrNull(), msg.trim(), Origin.KSP)
        }
        kspShort.matchEntire(line)?.let { m ->
            val (tool, msg) = m.destructured
            val origin = if (tool.startsWith("ksp")) Origin.KSP else Origin.KAPT
            return Start(null, null, msg.trim(), origin)
        }
        return null
    }

    private fun classifyContinuation(line: String): ContKind {
        if (line.isBlank()) return ContKind.FLUSH
        return when {
            line.startsWith("symbol:") || line.startsWith("location:") -> ContKind.MSG_APPEND
            line.startsWith("at ") || line.startsWith("\tat ")
                    || line.startsWith("    ")
                    || line == "^"
                    || line.startsWith("> Task :") -> ContKind.STACK_APPEND

            else -> ContKind.FLUSH
        }
    }

    private fun newPending(start: Start): Pending =
        Pending(
            path = start.path,
            line = start.line,
            message = StringBuilder(start.msg),
            stack = mutableListOf(),
            origin = start.origin
        )

    private fun flushPending(pending: Pending, out: MutableList<Finding>, moduleDir: File) {
        val fileRel = normalizePath(pending.path, moduleDir)
        out += Finding(
            type = "COMPILER_ERROR",
            filePath = fileRel,
            line = pending.line,
            severity = "ERROR",
            message = pending.message.toString().trimEnd(),
            stacktrace = pending.stack.toList()
        )
    }

    private fun normalizePath(path: String?, moduleDir: File): String? {
        if (path == null) return null
        return try {
            val p = File(path).canonicalFile
            val base = moduleDir.canonicalFile
            val norm = p.path.replace('\\', '/')
            val baseNorm = base.path.replace('\\', '/')
            if (norm.startsWith(baseNorm)) {
                norm.removePrefix(baseNorm).trimStart('/', '\\')
            } else {
                norm
            }
        } catch (_: Throwable) {
            path.replace('\\', '/')
        }
    }
}
