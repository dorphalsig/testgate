package com.supernova.testgate

import com.supernova.testgate.audits.AuditResult
import com.supernova.testgate.audits.Status
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Global aggregator (one instance per build).
 * Receives results concurrently and emits a single final report at end-of-build.
 */
abstract class TestGateReportService :
    BuildService<TestGateReportService.Params>,
    AutoCloseable {

    interface Params : BuildServiceParameters {
        val outputFile: RegularFileProperty
        val uploadEnabled: Property<Boolean>
    }

    private val logger = Logging.getLogger(TestGateReportService::class.java)
    private val results = ConcurrentLinkedQueue<AuditResult>()
    private val uploader: ReportUploader = PasteRsUploader(logger)

    fun enqueue(result: AuditResult) {
        results.add(result)
    }

    override fun close() {
        val all = results.toList()

        // No audits reported anything → do nothing.
        if (all.isEmpty()) {
            return
        }

        val failed = all.filter { it.status == Status.FAIL }
        val outFile = parameters.outputFile.asFile.get()
        val rootRelativePath = "/build/reports/testgate-results.json"

        // 1) Serialize (pretty) and write locally
        val json = JsonUtil.toPrettyJson(all)
        try {
            outFile.parentFile?.mkdirs()
            outFile.writeText(json)
        } catch (e: Exception) {
            throw GradleException("Failed to write TestGate report to ${outFile.absolutePath}", e)
        }

        // 2) Try upload (optional; never blocks outcome)
        val uploadUrl = if (parameters.uploadEnabled.orNull != false) {
            try {
                uploader.uploadPrettyJson(json)
            } catch (e: Exception) {
                logger.warn("TestGate: upload failed: ${e.message}")
                null
            }
        } else null

        val onlineForDisplay = uploadUrl?.let { if (it.endsWith(".json")) it else "$it.json" } ?: "unavailable"

        // 3) Decide build result
        if (failed.isNotEmpty()) {
            val failedNames = failed.joinToString(", ") { "${it.module}:${it.name}" }
            val message = buildString {
                append("Build Failed. The following audits failed: ")
                append(failedNames)
                append(".\nLocal json: ")
                append(rootRelativePath)
                append("\nOnline json: ")
                append(onlineForDisplay)
            }
            throw GradleException(message)
        } else {
            logger.lifecycle("Test Gate passed")
        }
    }

}

/* ---------- Small, local JSON serializer (no extra deps) ---------- */

private object JsonUtil {
    fun toPrettyJson(list: List<AuditResult>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        list.forEachIndexed { idx, r ->
            if (idx > 0) sb.append(",\n")
            sb.append("  {")
            field(sb, "module", r.module); sb.append(", ")
            field(sb, "name", r.name); sb.append(", ")
            // findings array
            sb.append("\"findings\": [")
            r.findings.forEachIndexed { fIdx, f ->
                if (fIdx > 0) sb.append(", ")
                sb.append("{")
                field(sb, "type", f.type); sb.append(", ")
                nullableField(sb, "filePath", f.filePath); sb.append(", ")
                nullableNumberField(sb, "line", f.line); sb.append(", ")
                nullableField(sb, "severity", f.severity); sb.append(", ")
                field(sb, "message", f.message); sb.append(", ")
                // stacktrace array
                sb.append("\"stacktrace\": [")
                f.stacktrace.forEachIndexed { sIdx, s ->
                    if (sIdx > 0) sb.append(", ")
                    string(sb, s)
                }
                sb.append("]")
                sb.append("}")
            }
            sb.append("], ")
            numberField(sb, "tolerance", r.tolerance); sb.append(", ")
            numberField(sb, "findingCount", r.findingCount); sb.append(", ")
            field(sb, "status", r.status.name)
            sb.append("}")
        }
        sb.append("\n]\n")
        return sb.toString()
    }

    private fun field(sb: StringBuilder, name: String, value: String) {
        sb.append("\"").append(escape(name)).append("\": ")
        string(sb, value)
    }

    private fun nullableField(sb: StringBuilder, name: String, value: String?) {
        sb.append("\"").append(escape(name)).append("\": ")
        if (value == null) sb.append("null") else string(sb, value)
    }

    private fun numberField(sb: StringBuilder, name: String, value: Number) {
        sb.append("\"").append(escape(name)).append("\": ").append(value)
    }

    private fun nullableNumberField(sb: StringBuilder, name: String, value: Number?) {
        sb.append("\"").append(escape(name)).append("\": ")
        if (value == null) sb.append("null") else sb.append(value)
    }

    private fun string(sb: StringBuilder, value: String) {
        sb.append("\"").append(escape(value)).append("\"")
    }

    private fun escape(s: String): String {
        val out = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"'  -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
                }
            }
        }
        return out.toString()
    }
}
