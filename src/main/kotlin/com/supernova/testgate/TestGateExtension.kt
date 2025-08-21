package com.supernova.testgate

import com.supernova.testgate.audits.AuditResult
import org.gradle.api.provider.Provider

/**
 * Exposes a single, concurrency-safe callback audits will call.
 * The lambda defers to the global BuildService (singleton per build).
 */
open class TestGateExtension internal constructor(
    private val service: Provider<TestGateReportService>
) {
    val onAuditResult: (AuditResult) -> Unit = { result ->
        // Safe to resolve at execution time; no heavy work during configuration.
        service.get().enqueue(result)
    }
}
