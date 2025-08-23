package com.supernova.testgate.audits

import io.mockk.mockk
import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HarnessReuseAuditTest {

    @TempDir
    lateinit var tmp: File

    private fun write(path: String, content: String) {
        val f = File(tmp, path)
        f.parentFile.mkdirs()
        f.writeText(content.trimIndent())
    }

    private fun runAudit(
        data: Set<String> = setOf(
            "com.supernova.testing.BaseRoomTest",
            "com.supernova.testing.RoomTestDbBuilder",
            "com.supernova.testing.DbAssertionHelpers"
        ),
        sync: Set<String> = setOf(
            "com.supernova.testing.BaseSyncTest",
            "com.supernova.testing.JsonFixtureLoader",
            "com.supernova.testing.MockWebServerExtensions",
            "com.supernova.testing.SyncScenarioFactory"
        ),
        ui: Set<String> = setOf(
            "com.supernova.testing.UiStateTestHelpers",
            "com.supernova.testing.PreviewFactories"
        ),
        cross: Set<String> = setOf(
            "com.supernova.testing.TestEntityFactory",
            "com.supernova.testing.CoroutineTestUtils"
        ),
        whitelist: List<String> = emptyList()
    ): AuditResult {
        val audit = HarnessReuseAudit(
            module = "app",
            moduleDir = tmp,
            logger = mockk<Logger>(relaxed = true),
            dataHelpers = data,
            syncHelpers = sync,
            uiHelpers = ui,
            crossHelpers = cross,
            whitelistPatterns = whitelist
        )
        var result: AuditResult? = null
        audit.check { r -> result = r }
        return result!!
    }

    @Test
    fun `data test imports area-specific helper - PASS`() {
        write(
            "src/test/kotlin/com/supernova/data/db/UserDaoTest.kt",
            """
            package com.supernova.data.db
            import com.supernova.testing.BaseRoomTest
            class UserDaoTest
            """
        )
        val res = runAudit()
        assertEquals(Status.PASS, res.status)
        assertTrue(res.findings.isEmpty())
    }

    @Test
    fun `sync test imports only cross-layer helper - FAIL`() {
        write(
            "src/test/kotlin/com/supernova/sync/SyncServiceTest.kt",
            """
            package com.supernova.sync
            import com.supernova.testing.CoroutineTestUtils
            class SyncServiceTest
            """
        )
        val res = runAudit()
        assertEquals(Status.FAIL, res.status)
        assertEquals(1, res.findings.size)
        assertEquals("MissingCanonicalHelperImport", res.findings.first().type)
    }

    @Test
    fun `ui test imports area-specific helper - PASS`() {
        write(
            "src/test/kotlin/com/supernova/ui/UiStateReducerTest.kt",
            """
            package com.supernova.ui
            import com.supernova.testing.UiStateTestHelpers
            class UiStateReducerTest
            """
        )
        val res = runAudit()
        assertEquals(Status.PASS, res.status)
    }

    @Test
    fun `missing area helper import in data test - FAIL`() {
        write(
            "src/test/kotlin/com/supernova/data/NoHelperTest.kt",
            """
            package com.supernova.data
            import kotlin.test.assertTrue
            class NoHelperTest
            """
        )
        val res = runAudit()
        assertEquals(Status.FAIL, res.status)
        assertEquals("MissingCanonicalHelperImport", res.findings.first().type)
    }

    @Test
    fun `whitelist import bypasses Rule A - PASS`() {
        write(
            "src/test/kotlin/com/supernova/data/BetaTest.kt",
            """
            package com.supernova.data
            import com.supernova.legacy.helpers.LegacyBase
            class BetaTest
            """
        )
        val res = runAudit(whitelist = listOf("com.supernova.legacy..*"))
        assertEquals(Status.PASS, res.status)
        assertTrue(res.findings.isEmpty())
    }

    @Test
    fun `local clone in main sources - FAIL`() {
        write(
            "src/main/kotlin/com/supernova/feature/TestEntityFactory.kt",
            """
            package com.supernova.feature
            class TestEntityFactory
            """
        )
        val res = runAudit(
            cross = setOf(
                "com.supernova.testing.TestEntityFactory",
                "com.supernova.testing.CoroutineTestUtils"
            )
        )
        assertEquals(Status.FAIL, res.status)
        assertTrue(res.findings.any { it.type == "LocalHelperClone" && it.message.contains("TestEntityFactory") })
    }

    @Test
    fun `whitelist exact clone FQCN - PASS`() {
        write(
            "src/main/kotlin/com/supernova/feature/TestEntityFactory.kt",
            """
            package com.supernova.feature
            class TestEntityFactory
            """
        )
        val res = runAudit(
            cross = setOf(
                "com.supernova.testing.TestEntityFactory",
                "com.supernova.testing.CoroutineTestUtils"
            ),
            whitelist = listOf("com.supernova.feature.TestEntityFactory")
        )
        assertEquals(Status.PASS, res.status)
        assertTrue(res.findings.isEmpty())
    }

    @Test
    fun `no package line - Rule A skipped (default package)`() {
        write(
            "src/test/kotlin/FallbackTest.kt",
            """
        import com.supernova.testing.BaseRoomTest
        class FallbackTest
        """
        )
        val res = runAudit()
        // No area can be inferred; Rule A is skipped; no clones present => PASS
        assertEquals(Status.PASS, res.status)
    }

}
