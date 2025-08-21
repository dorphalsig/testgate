package com.supernova.testgate

import com.supernova.testgate.audits.AuditResult
import com.supernova.testgate.testutil.TestData
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.api.provider.Provider
import org.junit.jupiter.api.Test

class TestGateExtensionTest {

    @Test
    fun `onAuditResult forwards to service enqueue`() {
        val service = mockk<TestGateReportService>(relaxed = true)
        val provider = mockk<Provider<TestGateReportService>>()
        every { provider.get() } returns service

        val ext = TestGateExtension(provider)
        val r: AuditResult = TestData.resultPass()

        ext.onAuditResult(r)

        verify(exactly = 1) { service.enqueue(r) }
    }
}
