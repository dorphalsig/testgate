package com.supernova.testgate.conventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.*
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File

/**
 * TestGateConventionsPlugin
 * - Applies required quality tooling (Detekt, JaCoCo, JUnit 5)
 * - Guarantees XML-only reports at stable paths expected by TestGate
 * - Does NOT auto-apply AGP; reacts only when Android plugins are present
 */
class TestGateConventionsPlugin : Plugin<Project> {

    override fun apply(project: Project) = with(project) {
        // Always apply JaCoCo
        plugins.apply("jacoco")
        ensureIsAndroidFlag()

        // JUnit 5 everywhere
        configureJUnit5()

        var detektApplied = false
        fun applyDetektOnce() {
            if (!detektApplied) {
                detektApplied = true
                applyDetektOrFail()
            }
        }

        // Android wiring, only if Android plugins are present
        pluginManager.withPlugin("com.android.application") {
            onAndroid(mandatoryRunner = true)
            applyDetektOnce()
        }
        pluginManager.withPlugin("com.android.library") {
            onAndroid(mandatoryRunner = true)
            applyDetektOnce()
        }

        // Defer remaining setup until after other plugins are applied
        afterEvaluate {
            if (!isAndroid()) {
                configureJvmJacocoGuaranteed()
            }
            applyDetektOnce()
        }

        // Apply TestGate LAST so audits can finalize after producers are registered
        applyTestGateByClass()
    }
// ───────────── Core setup ─────────────

    private fun Project.configureJUnit5() {
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            reports.junitXml.required.set(true)
            reports.html.required.set(false)
        }
    }


    private fun Project.applyDetektOrFail() {
        runCatching {
            plugins.apply("io.gitlab.arturbosch.detekt")
        }.getOrElse { ex ->
            throw IllegalStateException(
                "Detekt plugin is required but could not be resolved. " +
                    "Add pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } } " +
                    "to settings.gradle(.kts), or add the detekt-gradle-plugin to build-logic dependencies.",
                ex
            )
        }

        val detektTasks = tasks.matching { it.javaClass.name == "io.gitlab.arturbosch.detekt.Detekt" }
        detektTasks.configureEach {
            val reports = this.javaClass.getMethod("getReports").invoke(this)
            val xml = reports.javaClass.getMethod("getXml").invoke(reports)
            (xml.javaClass.getMethod("getRequired").invoke(xml) as Property<Boolean>).set(true)
            (xml.javaClass.getMethod("getOutputLocation").invoke(xml) as RegularFileProperty)
                .set(layout.buildDirectory.file("reports/detekt/detekt.xml"))
            listOf("Html", "Txt", "Sarif", "Md").forEach { name ->
                val report = reports.javaClass.getMethod("get$name").invoke(reports)
                (report.javaClass.getMethod("getRequired").invoke(report) as Property<Boolean>).set(false)
            }
        }
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(detektTasks)
        }
    }


    // ───────────── Android path ─────────────

    private fun Project.onAndroid(mandatoryRunner: Boolean) {
        setAndroid(true)
        applyAndroidJUnit5Runner(mandatoryRunner)
        configureAndroidLint()
        registerAndroidJacocoReport()
    }

    /** Apply Mannodermaus JUnit5 plugin on Android; fail with help if unresolved. */
    private fun Project.applyAndroidJUnit5Runner(mandatory: Boolean) {
        runCatching {
            plugins.apply("de.mannodermaus.android-junit5")
        }.getOrElse { ex ->
            if (mandatory) {
                throw IllegalStateException(
                    "Android JUnit5 plugin is required on Android projects. " + "Ensure pluginManagement { google(); gradlePluginPortal() } is configured, " + "or add de.mannodermaus:android-junit5 to build-logic.",
                    ex
                )
            }
        }
        // Set runner if android extension is present (reflection to avoid AGP API dep)
        extensions.findByName("android")?.let { androidExt ->
            val defaultConfig =
                androidExt.javaClass.methods.firstOrNull { it.name == "getDefaultConfig" }?.invoke(androidExt)
            defaultConfig?.javaClass?.methods?.firstOrNull {
                    it.name == "setTestInstrumentationRunner" && it.parameterTypes.contentEquals(arrayOf(String::class.java))
                }?.invoke(defaultConfig, "de.mannodermaus.junit5.AndroidJUnit5")
        }
    }


    private fun Project.configureAndroidLint() {
        tasks.matching { it.name == "lintDebug" }.configureEach {
            val xml = layout.buildDirectory.file("reports/lint-results-debug.xml")
            val task = this
            runCatching {
                task.javaClass.getMethod("setXmlOutput", File::class.java)
                    .invoke(task, xml.get().asFile)
                task.javaClass.methods.firstOrNull { m ->
                    m.name == "setHtmlOutput" && m.parameterTypes.contentEquals(arrayOf(File::class.java))
                }?.invoke(task, null)
                task.javaClass.methods.firstOrNull { m ->
                    m.name == "setTextOutput" && m.parameterTypes.contentEquals(arrayOf(File::class.java))
                }?.invoke(task, null)
            }
        }
    }

    
    private fun Project.configureJacoco(report: JacocoReport) = with(report) {
        sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
        reports {
            xml.required.set(true)
            xml.outputLocation.set(
                layout.buildDirectory.file(
                    "reports/jacoco/testDebugUnitTestReport/testDebugUnitTestReport.xml"
                )
            )
            html.required.set(false)
            csv.required.set(false)
        }
    }


    /** Android: produce one JaCoCo XML for debug unit tests and wire into check. */
    private fun Project.registerAndroidJacocoReport() {
        val task = tasks.register<JacocoReport>("jacocoTestDebugUnitTestReport") {
            group = "verification"
            description = "Generates JaCoCo XML for Android unit tests (debug)."
            dependsOn(tasks.matching { it.name == "testDebugUnitTest" })
            executionData.from(layout.buildDirectory.file("jacoco/testDebugUnitTest.exec"))
            classDirectories.setFrom(androidDebugClassDirs())
            configureJacoco(this)
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
    }


    // ───────────── JVM path ─────────────

    /** JVM: configure existing jacocoTestReport or register one if absent (Kotlin-only modules). */
    private fun Project.configureJvmJacocoGuaranteed() {
        val hasStandard = tasks.names.contains("jacocoTestReport")
        val report = if (hasStandard) tasks.named<JacocoReport>("jacocoTestReport")
        else tasks.register<JacocoReport>("jacocoTestReport")

        report.configure {
            group = "verification"
            description = "Generates JaCoCo XML for JVM unit tests."
            dependsOn(tasks.matching { it.name == "test" })
            executionData.from(layout.buildDirectory.file("jacoco/test.exec"))
            classDirectories.setFrom(jvmMainClassDirs())
            configureJacoco(this)
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(report) }
    }


    /** Apply TestGate by class if present (same repository); skip silently if absent. */
    private fun Project.applyTestGateByClass() {
        try {
            @Suppress("UNCHECKED_CAST") val clazz =
                Class.forName("com.supernova.testgate.TestGatePlugin") as Class<Plugin<Project>>
            plugins.apply(clazz)
        } catch (_: ClassNotFoundException) {
            logger.lifecycle("TestGate plugin not found on classpath; skipping auto-apply.")
        }
    }


    // ───────────── helpers ─────────────

    private fun Project.ensureIsAndroidFlag() {
        if (!extensions.extraProperties.has("isAndroid")) {
            extensions.extraProperties["isAndroid"] = false
        }
    }

    private fun Project.setAndroid(value: Boolean) {
        extensions.extraProperties["isAndroid"] = value
    }

    private fun Project.isAndroid(): Boolean = (extensions.extraProperties.properties["isAndroid"] as? Boolean) == true

    private fun Project.jvmMainClassDirs(): ConfigurableFileCollection = files(
        layout.buildDirectory.dir("classes/kotlin/main"), layout.buildDirectory.dir("classes/java/main")
    )

    private fun Project.androidDebugClassDirs(): ConfigurableFileCollection = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("intermediates/javac/debug/classes"),
    ).asFileTree.matching {
        exclude("**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*")
        exclude("**/*\$inlined$*")
    }.let { files(it) }

}
