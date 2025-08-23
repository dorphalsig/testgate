package com.supernova.testgate

import com.supernova.testgate.audits.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.StandardOutputListener
import org.gradle.api.provider.Provider

/**
 * Core plugin applied to EACH subproject (root not required).
 * - Exposes a concurrency-safe callback via TestGateExtension.
 * - Registers a global BuildService aggregator (singleton per build).
 * - Leaves a placeholder where audits will be wired via finalizedBy.
 */
class TestGatePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val serviceProvider = registerGlobalReportService(project)

        // Make the callback available to this subproject (audits will call it).
        project.extensions.create(
            "testGate",
            TestGateExtension::class.java,
            serviceProvider
        )

        // Placeholder: where each audit's hidden tasks will be wired later.
        registerAuditWiring(project)
    }

    /**
     * Registers the TestStackPolicyAudit and wires it to relevant test tasks.
     *
     * Uses project.extensions.extraProperties["isAndroid"] to branch.
     * Reads:
     *  - testgate.stack.allowlist.files
     *  - testgate.stack.mainDispatcherRules
     */
    fun Project.registerTestStackAudit() {
        val allowFiles = getCsvProperty("testgate.stack.allowlist.files")
        val rules = getCsvProperty("testgate.stack.mainDispatcherRules")
        val predecessors = listOf("testDebugUnitTest", "test")

        val auditTask = tasks.register("testStackPolicyAudit") {
            group = "verification"
            description = "Runs TestGate TestStackPolicy audit (tolerance=0)."
            doLast {
                val callback = extensions.getByType(TestGateExtension::class.java).onAuditResult
                TestStackPolicyAudit(
                    module = name,
                    moduleDir = layout.projectDirectory.asFile,
                    logger = logger,
                    allowlistFiles = allowFiles,
                    mainDispatcherRuleFqcns = rules
                ).check(callback)
            }
        }

        tasks.filter { predecessors.contains(it.name) }.forEach {
            it.finalizedBy(auditTask)
        }
    }

    private fun registerGlobalReportService(project: Project): Provider<TestGateReportService> {
        val gradle = project.gradle
        return gradle.sharedServices.registerIfAbsent(
            "testGateReportService",
            TestGateReportService::class.java
        ) {
            // Always write the final JSON under the ROOT build dir.
            parameters.outputFile.set(
                project.rootProject.layout.buildDirectory.file("reports/testgate-results.json")
            )
            parameters.uploadEnabled.convention(true)
        }
    }

    private fun Project.registerDetektAudit() {

        val task = tasks.register("detektAudit") {
            doLast {
                val audit = DetektAudit(
                    module = name,
                    reportXml = layout.buildDirectory.file("reports/detekt/detekt.xml").get().asFile,
                    moduleDir = layout.projectDirectory.asFile,
                    tolerancePercent = findProperty("testgate.detekt.tolerancePercent") as Int?,
                    whitelistPatterns = getCsvProperty("testgate.detekt.whitelist.patterns"),
                    hardFailRuleIds = getCsvProperty("testgate.detekt.hardFailRuleIds"),
                    // ?:listOf("ForbiddenImport", "ForbiddenMethodCall", "RequireHarnessAnnotationOnTests"))
                    logger = logger
                )
                audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
            }
        }

        tasks.getByName("detekt").finalizedBy(task)
    }

    internal fun Project.registerHarnessReuseAudit() {

        fun default(vararg fqcns: String) = fqcns.toList()
        val dataHelpers = getCsvProperty(
            "testgate.harness.helpers.data", listOf(
                "com.supernova.testing.BaseRoomTest",
                "com.supernova.testing.RoomTestDbBuilder",
                "com.supernova.testing.DbAssertionHelpers"
            )
        ).toSet()

        val syncHelpers = (getCsvProperty("testgate.harness.helpers.sync").ifEmpty {
            default(
                "com.supernova.testing.BaseSyncTest",
                "com.supernova.testing.JsonFixtureLoader",
                "com.supernova.testing.MockWebServerExtensions",
                "com.supernova.testing.SyncScenarioFactory"
            )
        }).toSet()

        val uiHelpers = (getCsvProperty("testgate.harness.helpers.ui").ifEmpty {
            default(
                "com.supernova.testing.UiStateTestHelpers",
                "com.supernova.testing.PreviewFactories"
            )
        }).toSet()

        val crossHelpers = (getCsvProperty("testgate.harness.helpers.cross").ifEmpty {
            default(
                "com.supernova.testing.TestEntityFactory",
                "com.supernova.testing.CoroutineTestUtils"
            )
        }).toSet()

        val whitelist = getCsvProperty("testgate.harness.whitelist")

        val audit = HarnessReuseAudit(
            module = name,
            moduleDir = layout.projectDirectory.asFile,
            logger = logger,
            dataHelpers = dataHelpers,
            syncHelpers = syncHelpers,
            uiHelpers = uiHelpers,
            crossHelpers = crossHelpers,
            whitelistPatterns = whitelist
        )

        val callback = extensions.getByType(TestGateExtension::class.java).onAuditResult
        audit.check(callback)
    }

    fun Project.registerSqlFtsAudit() {
        val tolerancePercent = (findProperty("testgate.sqlFts.tolerancePercent") as String?)?.toIntOrNull()
        val whitelistPatterns = getCsvProperty("testgate.sqlFts.whitelist")


        val task = tasks.register("auditsSqlFts") {
            doLast {
                val audit = SqlFtsAudit(
                    module = name,
                    moduleDir = layout.projectDirectory.asFile,
                    tolerancePercent = tolerancePercent,
                    whitelistPatterns = whitelistPatterns,
                    logger = logger
                )

                audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
            }
        }


// Run audit after JVM compile tasks only
        tasks.matching { t -> isJvmCompileTaskName(t.name) }.configureEach {
            finalizedBy(task)
        }
    }


    private fun isJvmCompileTaskName(name: String): Boolean {
        val n = name.lowercase()
        if (!n.startsWith("compile")) return false
        return n.endsWith("kotlin") || n.endsWith("java") || n.contains("kotlin") || n.contains("java")
    }

    private fun Project.registerAndroidLintAudit() {

        val task = tasks.register("lintDebugAudit") {
            doLast {
                val audit = DetektAudit(
                    module = name,
                    reportXml = layout.buildDirectory.file("reports/lint-results-debug.xml").get().asFile,
                    moduleDir = layout.projectDirectory.asFile,
                    tolerancePercent = findProperty("testgate.detekt.tolerancePercent") as Int?,
                    whitelistPatterns = getCsvProperty("testgate.detekt.whitelist.patterns"),
                    hardFailRuleIds = getCsvProperty("testgate.detekt.hardFailRuleIds"),
                    logger = logger
                )
                audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
            }
        }

        tasks.getByName("lintDebug").finalizedBy(task)
    }

    fun Project.getCsvProperty(key: String, default: List<String> = emptyList()): List<String> =
        (findProperty(key) as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default


    fun Project.registerCompilationAuditWiring() {
        // Strict audit: any compiler error fails the module.
        val audit = CompilationAudit(
            module = name,
            moduleDir = layout.projectDirectory.asFile
        )

        // Configure ONLY the tasks we care about (Debug).
        // We assume non-parallel builds, so per-task hook/unhook is safe & simple.
        tasks.configureEach {
            val n = name
            val isDebugCompile =
                n == "compileDebugKotlin" ||
                        n == "compileDebugJavaWithJavac" ||
                        n.startsWith("kaptDebug") ||
                        n.startsWith("kspDebug")

            if (!isDebugCompile) return@configureEach

            // One listener per task so we can cleanly unhook in the finalizer.
            val stderrListener = StandardOutputListener { chunk ->
                audit.append(chunk as String)
            }

            doFirst {
                audit.registerCapture()
                logging.addStandardErrorListener(stderrListener)
            }

            // Per-task finalizer ensures we always unhook + parse, even if the task fails.
            val finalizerName = "${n}CompilationAuditFinalize"
            val finalizer = tasks.register(finalizerName) {
                // Unhook first, then parse & report.
                doFirst {
                    logging.removeStandardErrorListener(stderrListener)
                    audit.unregisterCapture()
                    val callback = extensions.getByType(TestGateExtension::class.java).onAuditResult
                    audit.check(callback)
                }
            }
            finalizedBy(finalizer)
        }
    }

    fun Project.registerStructureAudit() {
        val audit = StructureAudit(
            module = name,
            moduleDir = layout.projectDirectory.asFile,
            logger = logger
        )
        tasks.register("auditsStructure") {
            doLast {
                audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
            }
        }
    }


    /**
     * Intentionally a NO-OP for now.
     *
     * Later:
     * - Register hidden audit tasks (e.g. testgateForbiddenImport).
     * - Wire them with finalizedBy to parent tasks:
     *    JVM:    tasks.named("check") { finalizedBy(hiddenAuditTask) }
     *    Android: tasks.named("test<Variant>UnitTest") { finalizedBy(hiddenAuditTask) }
     *            and/or tasks.named("check") as needed.
     * - Inside those tasks, parse tool output and call:
     *      project.extensions.getByType(TestGateExtension::class.java)
     *          .onAuditResult(auditResult)
     */
    private fun registerAuditWiring(project: Project) {
        with(project) {
            registerCompilationAuditWiring()
            registerDetektAudit()
            registerAndroidLintAudit()
            registerHarnessReuseAudit()
            registerSqlFtsAudit()
            registerStructureAudit()
            registerTestStackAudit()
        }
    }
}
