package com.supernova.testgate

import org.gradle.api.Project
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class TestValidationViolation(
    val type: String,
    val filePath: String,
    val line: Int? = null,
    val className: String? = null,
    val testMethod: String? = null,
    val issue: String,
    val remediation: String
)

class TestValidators(private val project: Project) {

    fun validateCoroutineTests(): List<TestValidationViolation> {
        val allowClasses = getPropertySet("allowCoroutineTestClasses")
        val violations = mutableListOf<TestValidationViolation>()
        val classRegexes = allowClasses.map(::globToRegex)
        val suspendTestRegex = Regex("""@Test(?:\s*\r?\n\s*)?suspend\s+fun\s+(\w+)""")

        project.fileTree("src")
            .matching { include("**/*.kt") }
            .filter { it.path.contains("/test/") }
            .forEach { file ->
                val content = file.readText()
                val fqcn = extractFqcn(content, file)

                if (classRegexes.any { it.matches(fqcn) }) return@forEach

                val relativePath = file.relativeTo(project.projectDir).path

                // Forbid runBlockingTest
                content.lineSequence().forEachIndexed { idx, line ->
                    if ("runBlockingTest" in line) {
                        violations += TestValidationViolation(
                            type = "COROUTINE_TEST",
                            filePath = relativePath,
                            line = idx + 1,
                            issue = "runBlockingTest usage",
                            remediation = "Replace runBlockingTest with runTest"
                        )
                    }
                }

                // Enforce runTest in suspend @Test methods
                for (match in suspendTestRegex.findAll(content)) {
                    val method = match.groupValues[1]
                    val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                    val tail = content.substring(match.range.last)
                    if (!tail.contains("runTest")) {
                        violations += TestValidationViolation(
                            type = "COROUTINE_TEST",
                            filePath = relativePath,
                            line = lineNum,
                            testMethod = method,
                            issue = "missing runTest in suspend test",
                            remediation = "Wrap suspend test body with runTest { ... }"
                        )
                    }
                }
            }
        return violations
    }

    fun validateTestStructure(): List<TestValidationViolation> {
        val allowClasses = getPropertySet("allowBadStructureTestClasses")
        val violations = mutableListOf<TestValidationViolation>()
        val allowedBases = listOf("BaseRoomTest", "BaseSyncTest", "TestEntityFactory", "UiStateTestHelpers")

        listOf("src/test", "src/androidTest").forEach { testDir ->
            project.fileTree(testDir)
                .matching { include("**/*.kt", "**/*.java") }
                .forEach { file ->
                    val content = file.readText()
                    val fqcn = extractFqcn(content, file)

                    if (matchesGlob(fqcn, allowClasses)) return@forEach

                    val classOk = allowedBases.any { base ->
                        Regex("""class\s+\w+\s*(?::|extends)\s*$base""").containsMatchIn(content)
                    }
                    val fixtureOk = Regex("""runTest\s*\(""").containsMatchIn(content) &&
                            Regex("""load[A-Za-z]*Fixture\s*\(""").containsMatchIn(content)

                    if (!classOk && !fixtureOk) {
                        violations += TestValidationViolation(
                            type = "TEST_STRUCTURE",
                            filePath = file.relativeTo(project.projectDir).path,
                            className = fqcn,
                            issue = "invalid test structure",
                            remediation = "Extend allowed base class (${allowedBases.joinToString()}) or use runTest with fixture loading"
                        )
                    }
                }
        }
        return violations
    }

    fun validateIgnoredTests(): List<TestValidationViolation> {
        val allowSkipped = getPropertySet("allowSkippedTests")
        val violations = mutableListOf<TestValidationViolation>()
        val resultsDir = project.buildFile.parentFile.resolve("build/test-results")

        if (!resultsDir.exists()) return violations

        val factory = DocumentBuilderFactory.newInstance()
        val parser = factory.newDocumentBuilder()

        project.fileTree(resultsDir)
            .matching { include("**/*.xml") }
            .forEach { xmlFile ->
                try {
                    val doc = parser.parse(xmlFile)
                    val testCases = doc.getElementsByTagName("testcase")

                    for (i in 0 until testCases.length) {
                        val testCase = testCases.item(i) as Element
                        if (testCase.getElementsByTagName("skipped").length > 0) {
                            val className = testCase.getAttribute("classname")
                            val testName = testCase.getAttribute("name")

                            if (testName !in allowSkipped) {
                                violations += TestValidationViolation(
                                    type = "IGNORED_TEST",
                                    filePath = "test-results",
                                    className = className,
                                    testMethod = testName,
                                    issue = "unapproved test skip",
                                    remediation = "Fix test or add '$testName' to allowSkippedTests property"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    project.logger.debug("Error parsing ${xmlFile.name}: ${e.message}")
                }
            }
        return violations
    }

    private fun getPropertySet(propertyName: String): Set<String> =
        (project.findProperty(propertyName) as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    private fun extractFqcn(text: String, file: java.io.File): String {
        val pkg = Regex("""^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)
        val cls = Regex("""class\s+(\w+)""").find(text)?.groupValues?.get(1)
        return if (pkg != null && cls != null) "$pkg.$cls"
        else file.relativeTo(project.projectDir).path.removeSuffix(".kt").replace('/', '.')
    }

    private fun globToRegex(glob: String): Regex {
        val regex = glob
            .split('.')
            .joinToString("\\.") { part ->
                when (part) {
                    "*" -> "[^.]*"
                    "**" -> ".*"
                    else -> Regex.escape(part)
                }
            }
        return Regex("^$regex$")
    }

    private fun matchesGlob(className: String, patterns: Set<String>): Boolean =
        patterns.any { pattern ->
            Regex("^" + pattern
                .replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^.]*") + "$"
            ).matches(className)
        }
}