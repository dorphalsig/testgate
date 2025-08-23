package com.supernova.testgate.audits

import org.gradle.api.logging.Logger
import java.io.File

/**
 * Harness reuse & isolation audit.
 *
 * Rule A (src/test/ ** only):
 *   Each test file under com.<root>.{data|sync|ui}..* MUST import ≥1 area-specific helper.
 *   Cross-layer helpers are allowed but NOT sufficient.
 *   If the file has no 'package' declaration (default package), Rule A is skipped.
 *   A file is skipped if any of its imports match the whitelist.
 *
 * Rule B (project-wide):
 *   No local clones of canonical helper simple names outside com.<root>.testing..*
 *   (Uses only declared package; if none, treats as default package).
 *   A clone is skipped if its FQCN matches the whitelist.
 *
 * Tolerance: 0 (hard fail).
 */
class HarnessReuseAudit(
    private val module: String,
    private val moduleDir: File,
    private val logger: Logger,
    private val dataHelpers: Collection<String>,
    private val syncHelpers: Collection<String>,
    private val uiHelpers: Collection<String>,
    private val crossHelpers: Collection<String>,
    whitelistPatterns: Collection<String> = emptyList()
) : Audit {

    private val rootNamespace: String = "com.supernova"
    private val harnessPackage: String = "com.supernova.testing"

    // ---- configuration (immutable, normalized) ----

    private val whitelist = WhitelistMatcher(whitelistPatterns)
    // Keep raw patterns to support the `..*` FQCN shorthand (any subpackage/class).
    private val rawWhitelist: List<String> = whitelistPatterns.toList()

    private val canonicalSimpleNames: Set<String> by lazy {
        (this@HarnessReuseAudit.dataHelpers + this@HarnessReuseAudit.syncHelpers + this@HarnessReuseAudit.uiHelpers + this@HarnessReuseAudit.crossHelpers).map { it.substringAfterLast('.') }
            .toSet()
    }

    private enum class Area { DATA, SYNC, UI }

    override fun check(callback: (AuditResult) -> Unit) {
        val findings = mutableListOf<Finding>()

        try {
// 1) Parse src/test/** files and enforce Rule A
            enforceMandatoryAreaHelper(findings)

// 2) Parse all sources and enforce Rule B
            detectLocalHelperClones(findings)

// 3) Aggregate
            val tolerance = 0
            val status = if (findings.size > tolerance) Status.FAIL else Status.PASS
            val result = AuditResult(
                module = module,
                name = "HarnessReuseIsolationAudit",
                findings = findings,
                tolerance = tolerance,
                findingCount = findings.size,
                status = status
            )

// 4) Callback
            callback(result)

            logger.info(
                "[TestGate] HarnessReuseIsolationAudit: ${findings.size} finding(s), status=$status in module '$module'"
            )
        } catch (t: Throwable) {
            throw IllegalStateException(
                "HarnessReuseIsolationAudit failed due to an unexpected error. See cause for details.", t
            )
        }
    }

// ---------- Rule A: src/test/** must import ≥1 area-specific helper ----------

    private fun enforceMandatoryAreaHelper(findings: MutableList<Finding>) {
        val testRoots = listOf(
            moduleDir.resolve("src/test/java"), moduleDir.resolve("src/test/kotlin")
        )

        testRoots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .forEach { file ->
                    val header = readHeader(file)
                    val pkg = header.pkg ?: ""
                    val area = areaForPackage(pkg) ?: return@forEach // default/no area => skip Rule A

// Skip if any import is whitelisted
                    if (header.imports.any { isWhitelistedImport(it) }) return@forEach

                    val areaFqcns = areaHelperSet(area)
                    val hasAreaHelper = header.imports.any { it in areaFqcns }
                    if (!hasAreaHelper) {
                        findings += Finding(
                            type = "MissingCanonicalHelperImport",
                            filePath = relative(file),
                            line = header.pkgLine ?: 1,
                            severity = "error",
                            message = buildString {
                                append("Test file under '${areaPackage(area)}' must import at least one of: ")
                                append(areaFqcns.joinToString(", ") { it.substringAfterLast('.') })
                            })
                    }
                }
        }
    }

// ---------- Rule B: no local clones outside harness package ----------

    private fun detectLocalHelperClones(findings: MutableList<Finding>) {
        val srcRoots = listOf("main", "debug", "release", "test", "androidTest").flatMap { ss ->
            listOf(
                moduleDir.resolve("src/$ss/java"), moduleDir.resolve("src/$ss/kotlin")
            )
        }

        srcRoots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                .forEach { file ->
                    val header = readHeader(file)
                    val pkg = header.pkg ?: "" // default package = empty string
                    if (pkg.startsWith(harnessPackage)) return@forEach // canonical home

                    header.classDecls.forEach { (name, line) ->
                        if (name in canonicalSimpleNames) {
                            val fqcn = if (pkg.isNotBlank()) "$pkg.$name" else name
                            if (whitelist.matchesFqcnOrSymbol(fqcn)) return@forEach
                            findings += Finding(
                                type = "LocalHelperClone",
                                filePath = relative(file),
                                line = line,
                                severity = "error",
                                message = "Local class '$name' shadows canonical helper in '$harnessPackage'. Move or delete to prevent forks."
                            )
                        }
                    }
                }
        }
    }

// ---------- helpers ----------

    /**
     * Accepts both WhitelistMatcher’s native FQCN matching and the shorthand pattern
     * form `"com.example.legacy..*"` meaning “anything under this package (any depth)”.
     */
    private fun isWhitelistedImport(fqcn: String): Boolean {
        if (whitelist.matchesFqcnOrSymbol(fqcn)) return true
        for (pat in rawWhitelist) {
            val i = pat.indexOf("..*")
            if (i >= 0 && fqcn.startsWith(pat.substring(0, i))) return true
        }
        return false
    }

    private fun areaForPackage(pkg: String): Area? {
        val base = "$rootNamespace."
        return when {
            pkg.startsWith(base + "data") -> Area.DATA
            pkg.startsWith(base + "sync") -> Area.SYNC
            pkg.startsWith(base + "ui") -> Area.UI
            else -> null
        }
    }

    private fun areaPackage(area: Area): String = when (area) {
        Area.DATA -> "$rootNamespace.data"
        Area.SYNC -> "$rootNamespace.sync"
        Area.UI -> "$rootNamespace.ui"
    }

    private fun areaHelperSet(area: Area): Collection<String> = when (area) {
        Area.DATA -> dataHelpers
        Area.SYNC -> syncHelpers
        Area.UI -> uiHelpers
    }

    private fun relative(file: File): String = file.relativeToOrSelf(moduleDir).invariantSeparatorsPath
}
