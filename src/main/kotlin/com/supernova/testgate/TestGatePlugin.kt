package com.supernova.testgate

import com.supernova.testgate.audits.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.StandardOutputListener
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskCollection

/**
 * Core plugin applied to EACH subproject (root not required).
 * - Exposes a concurrency-safe callback via TestGateExtension.
 * - Registers a global BuildService aggregator (singleton per build).
 * - Leaves a placeholder where audits will be wired via finalizedBy.
 */
class TestGatePlugin : Plugin<Project> {


    val Project.testTasks get() = tasks.matching { listOf("testDebugUnitTest", "test").contains(name) }

    val Project.compilationTasks
        get() = tasks.matching {
            name == ("compileDebugKotlin") ||
                    name == ("compileDebugJavaWithJavac") ||
                    name.startsWith("kaptDebug") ||
                    name.startsWith("kspDebug")
        }


    fun Project.getCsvProperty(key: String, default: List<String> = emptyList()): List<String> =
        (findProperty(key) as? String)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: default


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

    private fun Project.registerFixturesAudit() {
        val audit = FixturesAudit(
            module = name,
            moduleDir = layout.projectDirectory.asFile,
            tolerancePercent = (findProperty("testgate.fixtures.tolerancePercent") as String?)?.toInt(),
            minBytes = (findProperty("testgate.fixtures.minBytes") as String?)?.toInt() ?: 256,
            maxBytes = (findProperty("testgate.fixtures.maxBytes") as String?)?.toInt() ?: 8192,
            whitelistPatterns = getCsvProperty("testgate.fixtures.whitelist.patterns"),
            logger = logger
        )
        val task = tasks.register("runFixturesAudit") {
            doLast {
                audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
            }
        }
        compilationTasks.configureEach { finalizedBy(task) }
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

        val auditTask = tasks.register("testStackPolicyAudit") {
            doLast {
                val callback = extensions.getByType(TestGateExtension::class.java).onAuditResult
                TestStackAudit(
                    module = name,
                    moduleDir = layout.projectDirectory.asFile,
                    logger = logger,
                    whitelistPaths = allowFiles,
                ).check(callback)
            }
        }

        compilationTasks.configureEach {
            finalizedBy(auditTask)
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

        tasks.matching { name == "detekt" }.configureEach {
            finalizedBy(task)
        }
    }

    private fun Project.registerHarnessReuseAudit() {

        val dataHelpers = getCsvProperty(
            "testgate.harness.helpers.data", listOf(
                "com.supernova.testing.BaseRoomTest",
                "com.supernova.testing.RoomTestDbBuilder",
                "com.supernova.testing.DbAssertionHelpers"
            )
        ).toSet()

        val syncHelpers = getCsvProperty(
            "testgate.harness.helpers.sync", listOf(
                "com.supernova.testing.BaseSyncTest",
                "com.supernova.testing.JsonFixtureLoader",
                "com.supernova.testing.MockWebServerExtensions",
                "com.supernova.testing.SyncScenarioFactory"
            )
        )

        val uiHelpers = getCsvProperty(
            "testgate.harness.helpers.ui", listOf(
                "com.supernova.testing.UiStateTestHelpers",
                "com.supernova.testing.PreviewFactories"
            )
        )

        val crossHelpers = getCsvProperty(
            "testgate.harness.helpers.cross", listOf(
                "com.supernova.testing.TestEntityFactory",
                "com.supernova.testing.CoroutineTestUtils"
            )
        )

        val whitelist = getCsvProperty("testgate.harness.whitelist")

        tasks.register("harnessReuseAudit") {
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
        compilationTasks.configureEach { finalizedBy("harnessReuseAudit") }

    }

    private fun Project.registerSqlFtsAudit() {
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

        compilationTasks.configureEach {
            finalizedBy(task)
        }
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

    private fun Project.registerCompilationAuditWiring() {
        val audit = CompilationAudit(
            module = name,
            moduleDir = layout.projectDirectory.asFile
        )

        val auditTask = tasks.register("compilationAudit") {
            doLast {
                val callback = extensions.getByType(TestGateExtension::class.java).onAuditResult
                audit.check(callback)
            }
        }

        compilationTasks.configureEach {
            val stderrListener = StandardOutputListener { chunk ->
                audit.append(chunk as String)
            }

            doFirst {
                audit.registerCapture()
                logging.addStandardErrorListener(stderrListener)
            }

            val cleanup = tasks.register("${name}Cleanup") {
                doFirst {
                    logging.removeStandardErrorListener(stderrListener)
                    audit.unregisterCapture()
                }
            }
            finalizedBy(cleanup, auditTask)
        }
    }

    private fun Project.registerStructureAudit() {
        val task = tasks.register("structureAudit") {
            doLast {
                val audit = StructureAudit(
                    module = name,
                    moduleDir = layout.projectDirectory.asFile,
                    logger = logger
                )
                audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
            }
        }
        compilationTasks.configureEach {
            finalizedBy(task)
        }

    }

    private fun Project.registerTestsAudit() {
        // Collect all JVM unit-test tasks for this module
        val unitTests = tasks.withType(org.gradle.api.tasks.testing.Test::class.java)

        // Task that runs AFTER unit tests and evaluates JUnit XML
        val task = tasks.register("testGateAuditsTests") {
            doLast {
                unitTests.forEach { testTask ->
                    val xmlDir = testTask.reports.junitXml.outputLocation.get().asFile
                    val audit = TestsAudit(
                        module = project.name,
                        resultsDir = xmlDir,
                        tolerancePercent = (findProperty("testgate.tests.tolerancePercent") as? String)?.toIntOrNull(),
                        whitelistPatterns = getCsvProperty("testgate.tests.whitelist.patterns"),
                        logger = logger
                    )
                    audit.check(extensions.getByType(TestGateExtension::class.java).onAuditResult)
                }
            }
        }
        testTasks.configureEach { finalizedBy(task) }
    }



    /**
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
            registerFixturesAudit()
            registerTestsAudit()
        }
    }
}
