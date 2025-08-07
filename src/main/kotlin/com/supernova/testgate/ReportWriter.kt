package com.supernova.testgate

import org.gradle.api.Project

class ReportWriter(
    private val project: Project,
    private val aggregator: TaskResultAggregator,
    private val imports: List<ImportViolation>,
    private val assertions: List<TestFailureDetail>,
    private val coveragePct: Double,
    private val detektPct: Double,
    private val testViolations: List<TestValidationViolation>
) {

    fun generateReportMap(): Map<String, Any?> {
        // thresholds from gradle.properties (with fallbacks)
        val testTol = (project.findProperty("testFailTolerance") as? String)?.toInt() ?: 0
        val covTarget = (project.findProperty("coverageTarget") as? String)?.toDouble() ?: 0.0
        val detTol = (project.findProperty("detektTolerance") as? String)?.toDouble() ?: 0.0
        val frameLimit = (project.findProperty("stackTraceFrames") as? String)?.toInt() ?: 5

        // helper to format a Throwable's stack trace into a list of strings,
        // limited to 'frameLimit' entries
        fun formatStack(ex: Throwable): List<String> =
            ex.stackTrace
                .take(frameLimit)
                .map { st -> "${st.className}.${st.methodName}(${st.fileName}:${st.lineNumber})" }

        val reportMap = mutableMapOf<String, Any?>(
            "compileErrors" to aggregator.compileErrors().map { result ->
                val ex = result.failure!!
                mapOf(
                    "file" to (ex.stackTrace.firstOrNull()?.fileName ?: "unknown"),
                    "message" to (ex.message ?: ""),
                    "stackTrace" to formatStack(ex)
                )
            },
            "testFailures" to aggregator.testFailures().map { result ->
                mapOf(
                    "taskPath" to result.taskPath,
                    "taskName" to result.taskPath.substringAfterLast(":"),
                    "message" to (result.failure?.message ?: "")
                )
            },
            "importViolations" to imports.map {
                mapOf(
                    "filePath" to it.filePath,
                    "imported" to it.imported,
                    "violationType" to it.violationType
                )
            },
            "assertionDetails" to assertions.map {
                mapOf(
                    "className" to it.className,
                    "testName" to it.testName,
                    "message" to it.message,
                    "stackTrace" to it.stackTrace
                )
            },
            "testViolations" to testViolations.map {
                mapOf(
                    "type" to it.type,
                    "filePath" to it.filePath,
                    "line" to it.line,
                    "className" to it.className,
                    "testMethod" to it.testMethod,
                    "issue" to it.issue,
                    "remediation" to it.remediation
                )
            },
            "coveragePercent" to coveragePct,
            "detektIssuePercent" to detektPct
        )

        // threshold flags
        if (aggregator.testFailures().size > testTol) reportMap["testThresholdExceeded"] = true
        if (coveragePct < covTarget) reportMap["coverageThresholdExceeded"] = true
        if (detektPct > detTol) reportMap["detektThresholdExceeded"] = true

        return reportMap
    }
}