package com.supernova.testgate.convention

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.supernova.testgate.TestGatePlugin
import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.maybeCreate
import org.gradle.kotlin.dsl.withType
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File

/**
 * TestGateConventionPlugin
 * - Applies TestGate + quality tooling
 * - Produces per-variant Jacoco XML for Android unit tests (avoids aggregation)
 * - Piggybacks JVM/KMP jacocoTestReport
 * - Exposes properties for TestGate:
 *   - isAndroid: Boolean
 *   - currentTestVariant: String (last seen variant, e.g., "Debug")
 *   - executedTestTasks: String (comma-separated list of test task names that actually ran)
 */
class TestGateConventionPlugin : Plugin<Project> {

    private val androidPlugins = listOf("com.android.application", "com.android.library")
    private val kotlinPlugins =
        listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform")

    override fun apply(target: Project) = with(target) {
        // Exposed flags for consumers (e.g., TestGate analyzers)
        extensions.extraProperties["isAndroid"] = false
        extensions.extraProperties["currentTestVariant"] = ""
        extensions.extraProperties["executedTestTasks"] = ""

        applyPlugins()
    }

    // --- Apply baseline plugins ---------------------------------------------------------------
    private fun Project.applyPlugins() {
        //pluginManager.apply("com.supernova.testgate")
        plugins.apply(TestGatePlugin::class.java)
        (androidPlugins + kotlinPlugins).forEach { pid ->
            pluginManager.withPlugin(pid) {
                pluginManager.apply("io.gitlab.arturbosch.detekt")
                pluginManager.apply("jacoco")
                if (pid in androidPlugins) extensions.extraProperties["isAndroid"] = true
                wireCustomDetektRules()
                configureDetekt()
                configureAndroidLint()
                configureJacoco()
                configureJUnitWiring()
                // NEW: lightweight instrumentation aliases (safe, variant-aware)
                configureAndroidInstrumentationTasks()
                captureExecutedTestTasks()
            }
        }
    }

    // --- Detekt -------------------------------------------------------------------------------

    private fun Project.configureDetekt() {
        // Use the actual file name you keep in the repo:
        val detektConfig = File(rootDir, "config/detekt-config.yml")
        tasks.withType<Detekt>().configureEach {
            // Point Detekt to your config
            config.setFrom(files(detektConfig))

            // Reports: enable XML for your audits, keep others off
            reports {
                xml.required.set(true)
                html.required.set(true)
                txt.required.set(true)
                sarif.required.set(true)
            }

            // Keep build green; your audit will fail the build based on XML later
            ignoreFailures = true
        }
    }

    private fun Project.wireCustomDetektRules() {
        // Prefer: add THIS plugin's jar to detekt's plugin classpath so our ruleset is discovered.
        // Detekt looks up META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider on its own classpath.
        // Adding our plugin jar to the detektPlugins configuration exposes that service.
        runCatching {
            val url = this@TestGateConventionPlugin::class.java.protectionDomain.codeSource.location
            val selfJar = File(url.toURI())
            if (selfJar.exists()) {
                dependencies.add("detektPlugins", files(selfJar))
            }
        }

        // Fallback: if a prebuilt rules jar is kept under tools/, wire it too.
        val rulesJar = File(rootDir, "tools/testgate-detekt.jar")
        if (rulesJar.exists()) {
            dependencies.add("detektPlugins", files(rulesJar))
        }

        // Alternative if rules live in another included build/module:
        // dependencies.add("detektPlugins", project(":testgate-detekt"))
    }

    // --- Android Lint -------------------------------------------------------------------------
    private fun Project.configureAndroidLint() {
        androidPlugins.forEach { pid ->
            pluginManager.withPlugin(pid) {
                (extensions.findByName("android") as? CommonExtension<*, *, *, *, *, *>)?.lint {
                    lintConfig = File(rootDir,"config/lint-config.xml")
                    abortOnError = false
                    warningsAsErrors = false
                    xmlReport = true
                }
            }
        }
    }

    // --- Jacoco (JVM/KMP + Android) -----------------------------------------------------------
    private fun Project.configureJacoco() {
        // JVM / KMP: ensure standard jacocoTestReport writes XML
        kotlinPlugins.forEach { pid ->
            pluginManager.withPlugin(pid) {
                tasks.matching { it.name == "jacocoTestReport" }.configureEach {
                    (this as JacocoReport).configureReports()
                }
            }
        }
        // Android: per-variant reports, one XML per unit-test variant
        configureJacocoForAndroid()
    }

    private fun Project.configureJacocoForAndroid() {
        androidPlugins.forEach { pid ->
            pluginManager.withPlugin(pid) {
                // Keep your debug unit-test coverage toggle
                (extensions.findByName("android") as? CommonExtension<*, *, *, *, *, *>)?.let { android ->
                    android.buildTypes.getByName("debug") { enableUnitTestCoverage = true }
                }

                // SAFE registration point: Android Components API
                val ac = extensions.getByType(AndroidComponentsExtension::class.java)
                ac.onVariants { variant ->
                    val variantName = variant.name
                // e.g., "debug"
                    val Variant = variantName.replaceFirstChar { it.uppercase() } // "Debug"
                    val testTaskName = "test${Variant}UnitTest"
                    val reportTaskName = "jacoco${Variant}UnitTestReport"

                    // Reuse helper to register the report task
                    val reportTask = configureJacocoAndroidTasks(
                        reportTaskName = reportTaskName,
                        testTaskName = testTaskName,
                        variantLower = variantName,
                        variant = Variant
                    )

                    // Ensure the report runs after the matching test task (lazy + precise)
                    tasks.withType(Test::class.java).configureEach {
                        if (name == testTaskName) finalizedBy(reportTask)
                    }

                    // Expose most recent variant for consumers
                    extensions.extraProperties["currentTestVariant"] = Variant
                }
            }
        }
    }

    private fun Project.configureJacocoAndroidTasks(
        reportTaskName: String,
        testTaskName: String,
        variantLower: String,
        variant: String
    ): TaskProvider<JacocoReport> = tasks.register(reportTaskName, JacocoReport::class.java) {
        // FIX: depend on the test task (not on itself)
        dependsOn(tasks.named(testTaskName))
        configureReports()

        // Exec/EC files for this variant
        executionData.setFrom(
            fileTree(layout.buildDirectory) {
                include(
                    "jacoco/${testTaskName}*.exec",
                    "jacoco/${testTaskName}*.ec",
                    "outputs/unit_test_code_coverage/${variantLower}/${testTaskName}.exec",
                    "outputs/unit_test_code_coverage/${variantLower}/${testTaskName}.ec"
                )
            }
        )

        // Class dirs (Java + Kotlin)
        classDirectories.setFrom(
            files(
                layout.buildDirectory.dir("intermediates/javac/${variantLower}/classes"),
                layout.buildDirectory.dir("tmp/kotlin-classes/${variant}"),
                layout.buildDirectory.dir("tmp/kotlin-classes/${variantLower}")
            )
        )

        // Sources
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    }

    // --- Android Instrumentation aliases (safe, opt-in) --------------------------------------
    /**
     * Registers variant-aware alias tasks for instrumentation tests:
     * - instrumented<Variant>Test -> connected<Variant>AndroidTest
     * - instrumentedTest          -> depends on all instrumented<Variant>Test
     *
     * Notes:
     * - Does **not** change AGP behavior; only provides nicer entrypoints.
     * - Uses Android Components API to ensure variant names are correct.
     */
    private fun Project.configureAndroidInstrumentationTasks() {
        androidPlugins.forEach { pid ->
            pluginManager.withPlugin(pid) {
                val ac = extensions.getByType(AndroidComponentsExtension::class.java)
                ac.onVariants { variant ->
                    val variantLower = variant.name
                // e.g., "debug"
                    val Variant = variantLower.replaceFirstChar { it.uppercase() } // "Debug"
                    val connectedTask = "connected${Variant}AndroidTest"
                    val aliasName = "instrumented${Variant}Test"

                    // Per-variant alias (grouped under Verification)
                    val alias = tasks.register(aliasName) {
                        group = "verification"
                        description = "Runs instrumentation tests for ${Variant}"
                        dependsOn(connectedTask) // by name; AGP will create it
                    }

                    // Aggregate task across variants
                    val aggregate: Task = tasks.maybeCreate("instrumentedTest").apply {
                        group = "verification"
                        description = "Runs all instrumentation tests for all variants"
                    }
                    aggregate.dependsOn(alias)
                }
            }
        }
    }

    // --- JUnit wiring (name-based) ------------------------------------------------------------
    private fun Project.configureJUnitWiring() {
        // JVM: finalize `test` -> `jacocoTestReport`
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            wireFinalizer(
                "test",
                "jacocoTestReport"
            )
        }
        // KMP: finalize `jvmTest` -> `jacocoTestReport`
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            wireFinalizer(
                "jvmTest",
                "jacocoTestReport"
            )
        }
        // Android handled in configureJacocoForAndroid() per variant
    }

    // --- Capture executed test tasks (for exact filtering in TestGate) ------------------------
    private fun Project.captureExecutedTestTasks() {
        // At execution time, record the exact test task names that run in this project
        val project = this  // Capture the project reference
        gradle.taskGraph.whenReady {
            val executed = allTasks
                .filter { it.project == project }
                .map { it.name }
                .filter { name ->
                    name == "test" || name == "jvmTest" ||
                            (name.startsWith("test") && name.endsWith("UnitTest")) ||
                            name.startsWith("connected") && name.endsWith("AndroidTest") ||
                            name.startsWith("instrumented") && name.endsWith("Test")
                }
            if (executed.isNotEmpty()) {
                extensions.extraProperties["executedTestTasks"] = executed.joinToString(",")
            }
        }
    }

    // --- Helpers -----------------------------------------------------------------------------
    private fun JacocoReport.configureReports() {
        reports { xml.required.set(true); html.required.set(false); csv.required.set(false) }
    }

    /** Name-based: finalize [fromName] by [toName] when present. */
    private fun Project.wireFinalizer(fromName: String, toName: String) {
        tasks.matching { it.name == fromName }.configureEach { finalizedBy(tasks.named(toName)) }
    }
}

