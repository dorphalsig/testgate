package com.supernova.testgate

import com.supernova.testgate.testutil.TestData
import io.mockk.*
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.jupiter.api.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TestGateReportServiceTest {

    @TempDir
    lateinit var tmp: File

    private fun outputFile(): File =
        File(tmp, "reports/testgate-results.json")

    /** Create a concrete service instance with mocked Gradle parameters. */
    private fun newService(
        uploadEnabled: Boolean = true,
        outFile: File = outputFile()
    ): TestableService {
        val outputFileProp = mockk<RegularFileProperty>()
        val asFileProvider = mockk<Provider<File>>()
        every { asFileProvider.get() } returns outFile
        every { outputFileProp.asFile } returns asFileProvider

        val uploadEnabledProp = mockk<Property<Boolean>>()
        every { uploadEnabledProp.orNull } returns uploadEnabled

        val params = mockk<TestGateReportService.Params>()
        every { params.outputFile } returns outputFileProp
        every { params.uploadEnabled } returns uploadEnabledProp

        return TestableService(params)
    }

    /** Replace the private 'uploader' field via reflection. */
    private fun setUploader(service: TestableService, mock: ReportUploader) {
        val f: Field = TestGateReportService::class.java.getDeclaredField("uploader")
        f.isAccessible = true
        f.set(service, mock)
    }

    /** Concrete test subclass that returns our mocked Params. */
    private class TestableService(
        private val p: TestGateReportService.Params
    ) : TestGateReportService() {

        override fun getParameters(): Params = p
    }

    @Test
    fun `no results - close is a no-op (no file, no upload, no logs)`() {
        val service = newService(uploadEnabled = true)
        val uploader = mockk<ReportUploader>(relaxed = true)
        setUploader(service, uploader)

        service.close()

        Assertions.assertFalse(outputFile().exists(), "Report file should not be created when there are no results")
        verify { uploader wasNot Called }
    }

    @Test
    fun `all PASS with some tolerated findings - pass build and write JSON and try upload`() {
        val service = newService(uploadEnabled = true)
        val uploader = mockk<ReportUploader>()
        setUploader(service, uploader)

        val findings = listOf(TestData.finding())
        service.enqueue(TestData.resultPass(module = ":app", name = "ForbiddenImport", findings = findings))
        service.enqueue(TestData.resultPass(module = ":lib", name = "RequireHarnessAnnotationOnTests", findings = emptyList()))

        every { uploader.uploadPrettyJson(any()) } returns "http://paste.rs/xyz"

        Assertions.assertDoesNotThrow { service.close() }

        // JSON written
        val file = outputFile()
        Assertions.assertTrue(file.exists(), "Report JSON should be written")
        val content = file.readText()
        Assertions.assertTrue(content.startsWith("["), "Should be a JSON array")
        Assertions.assertTrue(content.contains("\"module\": \":app\""), "Should include module :app")
        Assertions.assertTrue(content.contains("\"name\": \"ForbiddenImport\""), "Should include audit name")

        // Upload attempted with JSON content
        verify(exactly = 1) { uploader.uploadPrettyJson(match { it.startsWith("[") && it.contains("ForbiddenImport") }) }
    }

    @Test
    fun `at least one FAIL - throws GradleException and prints correct message with json url`() {
        val service = newService(uploadEnabled = true)
        val uploader = mockk<ReportUploader>()
        setUploader(service, uploader)

        service.enqueue(TestData.resultPass(module = ":app", name = "ForbiddenImport"))
        service.enqueue(TestData.resultFail(module = ":lib", name = "ForbiddenMethodCall")) // FAIL

        every { uploader.uploadPrettyJson(any()) } returns "http://paste.rs/abc"

        val ex = Assertions.assertThrows(GradleException::class.java) {
            service.close()
        }
        val msg = ex.message ?: ""

        // Local path and online .json form must be present
        Assertions.assertTrue(msg.contains("Build Failed. The following audits failed"), "Should contain failure header")
        Assertions.assertTrue(msg.contains(":lib:ForbiddenMethodCall"), "Should list failing audit with module")
        Assertions.assertTrue(msg.contains("Local json: /build/reports/testgate-results.json"), "Should include local JSON path")
        Assertions.assertTrue(msg.contains("Online json: http://paste.rs/abc.json"), "Should include .json URL")

        // JSON should still be written even on failure
        Assertions.assertTrue(outputFile().exists(), "Report JSON should be written even when failing")
    }

    @Test
    fun `uploader fails but audits pass - still pass`() {
        val service = newService(uploadEnabled = true)
        val uploader = mockk<ReportUploader>()
        setUploader(service, uploader)

        service.enqueue(TestData.resultPass(module = ":app", name = "ForbiddenImport"))

        every { uploader.uploadPrettyJson(any()) } throws RuntimeException("network down")

        Assertions.assertDoesNotThrow { service.close() }
        Assertions.assertTrue(outputFile().exists(), "Report JSON should be written")
    }

    @Test
    fun `uploader fails and audits FAIL - still fail with Online json unavailable`() {
        val service = newService(uploadEnabled = true)
        val uploader = mockk<ReportUploader>()
        setUploader(service, uploader)

        service.enqueue(TestData.resultFail(module = ":core", name = "RequireHarnessAnnotationOnTests"))

        every { uploader.uploadPrettyJson(any()) } throws RuntimeException("timeout")

        val ex = Assertions.assertThrows(GradleException::class.java) { service.close() }
        val msg = ex.message ?: ""
        Assertions.assertTrue(msg.contains("Online json: unavailable"), "Should mark online JSON as unavailable on upload failure")
    }

    @Test
    fun `concurrent enqueues are all persisted once`() {
        val service = newService(uploadEnabled = false) // avoid upload noise
        val threads = 8
        val perThread = 50
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                repeat(perThread) { i ->
                    service.enqueue(
                        TestData.resultPass(
                            module = ":m${t}",
                            name = "ForbiddenImport",
                            findings = if (i % 10 == 0) listOf(TestData.finding(line = i)) else emptyList()
                        )
                    )
                }
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        pool.shutdown()

        Assertions.assertDoesNotThrow { service.close() }

        val file = outputFile()
        val content = file.readText()
        // Expect exact count
        val expected = threads * perThread
        // very light check: count of `"module":` occurrences equals expected
        val actual = "\"module\":".toRegex().findAll(content).count()
        Assertions.assertEquals(expected, actual, "All enqueued results should be serialized")
    }

    @Test
    fun `write failure throws GradleException preserving cause`() {
        // Point to a location that cannot be written by making parent a file
        val nonDirParent = File(tmp, "reports")
        nonDirParent.writeText("not a directory")
        val out = File(nonDirParent, "testgate-results.json")

        val service = newService(uploadEnabled = false, outFile = out)
        service.enqueue(TestData.resultPass())

        val ex = Assertions.assertThrows(GradleException::class.java) { service.close() }
        Assertions.assertTrue((ex.cause is Exception), "Original cause should be preserved")
        Assertions.assertTrue(ex.message?.contains("Failed to write TestGate report") == true)
    }

    @Test
    fun `uploader returns url without json - message uses json extension`() {
        val service = newService(uploadEnabled = true)
        val uploader = mockk<ReportUploader>()
        setUploader(service, uploader)

        service.enqueue(TestData.resultFail(module = ":app", name = "ForbiddenImport")) // to force failure

        every { uploader.uploadPrettyJson(any()) } returns "http://paste.rs/abc"

        val ex = Assertions.assertThrows(GradleException::class.java) { service.close() }
        val msg = ex.message ?: ""
        Assertions.assertTrue(msg.contains("Online json: http://paste.rs/abc.json"), "Must append .json to URL")
    }
}
