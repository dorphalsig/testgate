package com.supernova.testgate.conventions

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class FunctionalJvmTest {

    @Test
    fun `produces JaCoCo XML at TestGate path`() {
        val dir = createTempDir(prefix = "tg-simple-")
        File(dir, "settings.gradle.kts").writeText(
            """
        rootProject.name = "sample"
        pluginManagement {
          repositories {
            gradlePluginPortal()
            mavenCentral()
            google()
          }
        }
        """.trimIndent()
        )

        File(dir, "build.gradle.kts").writeText(
            """
        import java.io.File

        plugins {
            java
        }

        // Put the plugin-under-test on the buildscript classpath
        buildscript {
            dependencies {
                val cp = System.getProperty("plugin.classpath")
                    ?: error("plugin.classpath system property not set by TestKit")
                classpath(files(cp.split(File.pathSeparator)))
            }
        }

        // Apply your conventions plugin by ID from the buildscript classpath
        apply(plugin = "com.supernova.testgate.conventions")

        repositories { mavenCentral() }
        """.trimIndent()
        )

        File(dir, "src/main/java").mkdirs()
        File(dir, "src/main/java/App.java").writeText(
            """
        public class App { public static void main(String[] args) {} }
        """.trimIndent()
        )

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withArguments("clean", "test", "jacocoTestReport", "--stacktrace")
            .withPluginClasspath()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":test")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":jacocoTestReport")?.outcome)

        val xml = File(dir, "build/reports/jacoco/testDebugUnitTestReport/testDebugUnitTestReport.xml")
        assertTrue(xml.exists(), "Expected JaCoCo XML at TestGate path")
    }

    @Test
    fun `detekt produces XML at canonical path`() {
        val dir = createTempDir(prefix = "tg-detekt-")

        // Make plugin IDs resolvable during the TestKit build
        File(dir, "settings.gradle.kts").writeText(
            """
        rootProject.name = "sample-detekt"
        pluginManagement {
          repositories {
            gradlePluginPortal()
            mavenCentral()
            google()
          }
        }
        """.trimIndent()
        )

        // Apply the plugin-under-test via buildscript classpath + ID
        File(dir, "build.gradle.kts").writeText(
            """
        import java.io.File

        // No need for Java/Kotlin plugins; we just want Detekt to run and emit XML.
        buildscript {
            dependencies {
                val cp = System.getProperty("plugin.classpath")
                    ?: error("plugin.classpath system property not set by TestKit")
                classpath(files(cp.split(File.pathSeparator)))
            }
        }

        apply(plugin = "com.supernova.testgate.conventions")

        repositories {
            mavenCentral()
        }
        """.trimIndent()
        )

        // (Intentionally no sources: Detekt should still run and create an empty XML report)

        val result = GradleRunner.create()
            .withProjectDir(dir)
            .withArguments("clean", "detekt", "--stacktrace")
            .withPluginClasspath()
            .build()

        // Ensure detekt executed successfully
        assertEquals(TaskOutcome.SUCCESS, result.task(":detekt")?.outcome)

        // Validate canonical Detekt XML path and that other formats are disabled
        val xml = File(dir, "build/reports/detekt/detekt.xml")
        assertTrue(xml.exists(), "Expected Detekt XML at canonical path")

        // By convention plugin config, these should NOT be produced
        assertFalse(File(dir, "build/reports/detekt/detekt.html").exists(), "HTML report should be disabled")
        assertFalse(File(dir, "build/reports/detekt/detekt.txt").exists(), "TXT report should be disabled")
        assertFalse(File(dir, "build/reports/detekt/detekt.sarif").exists(), "SARIF report should be disabled")
        assertFalse(File(dir, "build/reports/detekt/detekt.md").exists(), "MD report should be disabled")
    }

}
