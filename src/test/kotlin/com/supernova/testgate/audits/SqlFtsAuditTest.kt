package com.supernova.testgate.audits

import org.gradle.api.logging.Logging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SqlFtsAuditTest {

    private val logger = Logging.getLogger(SqlFtsAuditTest::class.java)

    @Test
    fun `simple select passes`() {
        val dir = tempModule(
            "Dao.kt" to """
                import androidx.room.*
                interface D {
                  @Query("SELECT * FROM t WHERE id=:id")
                  fun one(id:Int): Int
                }
            """.trimIndent()
        )
        val audit = SqlFtsAudit(
            module = "test",
            moduleDir = dir,
            tolerancePercent = 0,
            whitelistPatterns = emptyList(),
            logger = logger
        )
        val result = run(audit)
        assertEquals(Status.PASS, result.status)
        assertEquals(0, result.findings.size)
    }

    @Test
    fun `raw query is banned`() {
        val dir = tempModule(
            "Dao.kt" to """
                import androidx.room.*
                import androidx.sqlite.db.SupportSQLiteQuery
                interface D {
                  @RawQuery fun all(q: SupportSQLiteQuery): Int
                }
            """.trimIndent()
        )
        val result = run(defaultAudit(dir))
        assertTrue(result.findings.any { it.type == "RawQueryUsage" })
        assertTrue(result.findings.any { it.type == "SupportSQLiteQueryUsage" })
        assertEquals(Status.FAIL, result.status)
    }

    @Test
    fun `complex query is banned`() {
        val dir = tempModule(
            "Dao.kt" to """
                import androidx.room.*
                interface D {
                  @Query("SELECT * FROM a JOIN b ON a.id=b.id")
                  fun bad(): Int
                }
            """.trimIndent()
        )
        val result = run(defaultAudit(dir))
        assertTrue(result.findings.any { it.type == "ComplexQueryInDao" })
        assertEquals(Status.FAIL, result.status)
    }

    @Test
    fun `whitelisted migrations allow complex query`() {
        val dir = tempModule(
            "src/main/java/com/app/migrations/Mig.kt" to """
                import androidx.room.*
                interface M {
                  @Query("WITH cte AS (SELECT 1) SELECT * FROM cte")
                  fun ok(): Int
                }
            """.trimIndent()
        )
        val result = run(defaultAudit(dir, whitelist = listOf("**/migrations/**")))
        assertTrue(result.findings.none { it.type == "ComplexQueryInDao" })
    }

    @Test
    fun `fts5 banned and fts4 required`() {
        val dir = tempModule(
            "Ent.kt" to """
                @Fts5
                annotation class F5
            """.trimIndent()
        )
        val result = run(defaultAudit(dir))
        assertTrue(result.findings.any { it.type == "Fts5Used" })
        assertTrue(result.findings.any { it.type == "FtsMissingFts4" })
        assertEquals(Status.FAIL, result.status)
    }

    @Test
    fun `rails guard requires order by position and forbids popularity`() {
        val dir = tempModule(
            "Dao.kt" to """
                import androidx.room.*
                interface D {
                  @Query("SELECT * FROM RailEntry WHERE x=1")
                  fun a(): List<Int>
                  @Query("SELECT * FROM RailEntry ORDER BY popularity DESC")
                  fun b(): List<Int>
                  @Query("SELECT * FROM RailEntry ORDER BY position ASC")
                  fun c(): List<Int>
                }
            """.trimIndent()
        )
        val result = run(defaultAudit(dir))
        assertTrue(result.findings.any { it.message.contains("must ORDER BY position") })
        assertTrue(result.findings.any { it.message.contains("popularity is forbidden") })
        assertTrue(result.findings.none { it.line == null })
    }

    // ---------- helpers ----------

    private fun run(audit: SqlFtsAudit): AuditResult {
        var res: AuditResult? = null
        audit.check { r -> res = r }
        return requireNotNull(res)
    }

    private fun defaultAudit(dir: File, whitelist: List<String> = emptyList()) = SqlFtsAudit(
        module = "test",
        moduleDir = dir,
        tolerancePercent = 0,
        whitelistPatterns = whitelist,
        logger = logger
    )

    private fun tempModule(vararg files: Pair<String, String>): File {
        val root = createTempDir(prefix = "mod")
        files.forEach { (rel, content) ->
            val f = File(root, rel)
            f.parentFile.mkdirs()
            f.writeText(content)
        }
        if (files.none { it.first.startsWith("src/") }) {
            // Place files under src/main/java if caller used simple names:
            val toMove = root.listFiles()?.filter { it.isFile } ?: emptyList()
            toMove.forEach { child ->
                val dst = File(root, "src/main/java/${child.name}")
                dst.parentFile.mkdirs()
                child.copyTo(dst, overwrite = true)
                child.delete()
            }
        }
        return root
    }
}
