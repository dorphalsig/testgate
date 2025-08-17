package com.supernova.testgate.audits

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.gradle.api.logging.Logging
import org.gradle.api.logging.StandardOutputListener
import org.gradle.internal.impldep.org.apache.commons.lang.NotImplementedException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Gradle task that captures compiler output and reports compile-time errors as Findings.
 * Responsibility: capture raw output lines during compilation and parse them later in [check].
 * Tolerance = 0. No whitelists. Warnings are ignored. Exceptions are wrapped in GradleException and rethrown.
 */
open class CompileAuditTask : DefaultTask(), Audit {

    /** Raw line holder. */
    private data class RawLine(val text: String)

    // Thread-safe buffer; capture is append-only during build.
    private val captured = CopyOnWriteArrayList<RawLine>()

    // Listener instances (added/removed in start/stop).
    private val stdoutListener = StandardOutputListener { line ->
        if (line != null) captured.add(RawLine(line.toString()))
    }
    private val stderrListener = StandardOutputListener { line ->
        if (line != null) captured.add(RawLine(line.toString()))
    }

    @Volatile private var capturing: Boolean = false
    private val activeCaptures = AtomicInteger(0)

    /**
     * Start capturing Gradle log output (stdout & stderr) via logging listeners.
     * No parsing here; we only buffer raw strings.
     */
    fun start() {
        try {
            activeCaptures.incrementAndGet()
            if (!capturing) {
                //todo: fix this
                throw GradleException("Compile Audit: Not Implemented")
//                Logging.addStandardOutputListener(stdoutListener)
//                Logging.addStandardErrorListener(stderrListener)
                capturing = true
            }
        } catch (e: Exception) {
            throw GradleException("CompileAudit: start() failed to attach logging listeners", e)
        }
    }

    /**
     * Stop capturing previously started logging listeners. Idempotent and reference-counted
     * to remain safe under parallel compilation.
     */
    fun stop() {
        try {
            val remaining = activeCaptures.decrementAndGet().coerceAtLeast(0)
            if (remaining == 0 && capturing) {
                //todo fix
//                Logging.removeStandardOutputListener(stdoutListener)
//                Logging.removeStandardErrorListener(stderrListener)
                capturing = false
            }
        } catch (e: Exception) {
            throw GradleException("CompileAudit: stop() failed to detach logging listeners", e)
        }
    }

    /** Testing helper to inject raw lines without relying on Gradle logging in unit tests. */
    fun ingestRawLines(lines: List<String>) {
        lines.forEach { captured.add(RawLine(it)) }
    }

    override fun check(callback: (CheckpointResult) -> Unit) {
        try {
            val lines = captured.map { it.text }
            val findings = parseCompilerOutput(lines)
            val result = CheckpointResult(
                module = project.name,
                name = "compile",
                findings = findings,
                tolerance = 0,
                findingCount = findings.size,
                status = if (findings.isEmpty()) Status.PASS else Status.FAIL
            )
            callback(result)
        } catch (e: Exception) {
            throw GradleException("CompileAudit: check() failed to assemble result", e)
        }
    }

    /**
     * Parse raw compiler output lines into Findings (errors only). Warnings are ignored.
     * Supports common Kotlin and Javac formats and attaches following context lines to the same finding
     * until a new error header is encountered.
     */
    internal fun parseCompilerOutput(lines: List<String>): List<Finding> {
        try {
            val findings = mutableListOf<Finding>()

            // Patterns
            val kt1 = Pattern.compile("^e:\\s+(.+?):\\s*\\((\\d+),\\s*(\\d+)\\):\\s*(.+)")
            val kt2 = Pattern.compile("^e:\\s+(.+?):(\\d+):\\s*(.+)")
            val javac = Pattern.compile("^(.+\\.java):(\\d+):\\s*error:\\s*(.+)")

            var current: FindingBuilder? = null

            fun flush() {
                current?.let { b -> findings.add(b.build()) }
                current = null
            }

            for (raw in lines) {
                val line = raw.trimEnd('\r')
                val lower = line.lowercase()
                // Skip pure warnings
                if (lower.startsWith("w:") || lower.contains(": warning:")) {
                    continue
                }

                // Kotlin with (line,col)
                var m = kt1.matcher(line)
                if (m.find()) {
                    flush()
                    current = FindingBuilder(
                        type = "compile",
                        filePath = m.group(1),
                        line = m.group(2).toIntOrNull(),
                        severity = "error",
                        message = m.group(4)
                    )
                    continue
                }

                // Kotlin fallback with just line
                m = kt2.matcher(line)
                if (m.find()) {
                    flush()
                    current = FindingBuilder(
                        type = "compile",
                        filePath = m.group(1),
                        line = m.group(2).toIntOrNull(),
                        severity = "error",
                        message = m.group(3)
                    )
                    continue
                }

                // Javac
                m = javac.matcher(line)
                if (m.find()) {
                    flush()
                    current = FindingBuilder(
                        type = "compile",
                        filePath = m.group(1),
                        line = m.group(2).toIntOrNull(),
                        severity = "error",
                        message = m.group(3)
                    )
                    continue
                }

                // Context line for current error
                if (current != null) {
                    if (line.isNotBlank()) {
                        current!!.stacktrace.add(line)
                    } else {
                        flush() // blank line ends block
                    }
                }
            }

            if (current != null) flush()
            return findings
        } catch (e: Exception) {
            throw GradleException("CompileAudit: parseCompilerOutput() failed", e)
        }
    }

    /** Simple builder to accumulate multi-line error context. */
    private class FindingBuilder(
        val type: String,
        val filePath: String?,
        val line: Int?,
        val severity: String?,
        val message: String
    ) {
        val stacktrace: MutableList<String> = mutableListOf()
        fun build(): Finding = Finding(type, filePath, line, severity, message, stacktrace.toList())
    }

    @TaskAction
    fun runAudit() {
        // Default behavior: assemble and log status; a plugin can also wire a callback collector.
        check { result ->
            logger.lifecycle("[CompileAudit] Module='${'$'}{result.module}' Findings=${'$'}{result.findings.size} Status=${'$'}{result.status}")
        }
    }
}
