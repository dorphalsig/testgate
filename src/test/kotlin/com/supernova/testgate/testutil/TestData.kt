package com.supernova.testgate.testutil

import com.supernova.testgate.audits.AuditResult
import com.supernova.testgate.audits.Finding
import com.supernova.testgate.audits.Status

object TestData {
    fun finding(
        type: String = "Detekt",
        filePath: String? = "app/src/main/kotlin/Foo.kt",
        line: Int? = 42,
        severity: String? = "ERROR",
        message: String = "Forbidden import: kotlin.io.printStackTrace",
        stacktrace: List<String> = emptyList()
    ) = Finding(type, filePath, line, severity, message, stacktrace)

    fun resultPass(
        module: String = ":app",
        name: String = "ForbiddenImport",
        findings: List<Finding> = emptyList(),
        tolerance: Int = 0
    ) = AuditResult(
        module = module,
        name = name,
        findings = findings,
        tolerance = tolerance,
        findingCount = findings.size,
        status = Status.PASS
    )

    fun resultFail(
        module: String = ":app",
        name: String = "ForbiddenMethodCall",
        findings: List<Finding> = listOf(finding()),
        tolerance: Int = 0
    ) = AuditResult(
        module = module,
        name = name,
        findings = findings,
        tolerance = tolerance,
        findingCount = findings.size,
        status = Status.FAIL
    )
}
