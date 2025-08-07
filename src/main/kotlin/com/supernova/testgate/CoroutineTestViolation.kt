package com.supernova.testgate

import org.gradle.api.Project

data class CoroutineTestViolation(
    val filePath: String, val line: Int, val issue: String, val method: String? = null
)

class CoroutineTestValidator(private val project: Project) {
    private val allowClasses: Set<String> by lazy {
        (project.findProperty("allowCoroutineTestClasses") as? String)?.split(',')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    private val suspendTestRegex = Regex("""@Test(?:\s*\r?\n\s*)?suspend\s+fun\s+(\w+)""")

    fun validate(): List<CoroutineTestViolation> {
        val violations = mutableListOf<CoroutineTestViolation>()
        val classRegexes = allowClasses.map(::globToRegex)

        project.fileTree("src").matching { include("**/*.kt") }
            .filter { it.path.contains("/test/") }.forEach { file ->
                val content = file.readText()
                val fqcn = extractFullyQualifiedClassName(content, file)

                // Skip allowed test classes entirely
                if (classRegexes.any { it.matches(fqcn) }) return@forEach

                val relativePath = file.relativeTo(project.projectDir).path

                // 1) Forbid runBlockingTest
                content.lineSequence().forEachIndexed { idx, line ->
                    if ("runBlockingTest" in line) {
                        violations += CoroutineTestViolation(
                            filePath = relativePath,
                            line = idx + 1,
                            issue = "use runTest instead of runBlockingTest"
                        )
                    }
                }

                // 2) Enforce runTest in suspend @Test methods
                for (match in suspendTestRegex.findAll(content)) {
                    val method = match.groupValues[1]
                    val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                    val tail = content.substring(match.range.last)
                    if (!tail.contains("runTest")) {
                        violations += CoroutineTestViolation(
                            filePath = relativePath,
                            line = lineNum,
                            issue = "missing runTest in suspend test method",
                            method = method
                        )
                    }
                }
            }

        return violations
    }

    private fun extractFullyQualifiedClassName(text: String, file: java.io.File): String {
        val pkg = Regex("""^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)
        val cls = Regex("""class\s+(\w+)""").find(text)?.groupValues?.get(1)
        return if (pkg != null && cls != null) "$pkg.$cls"
        else file.relativeTo(project.projectDir).path.removeSuffix(".kt").replace('/', '.')
    }

    private fun globToRegex(glob: String): Regex {
        val regex = glob.split('.').joinToString("\\.") { part ->
                when (part) {
                    "*" -> "[^.]*"
                    "**" -> ".*"
                    else -> Regex.escape(part)
                }
            }
        return Regex("^$regex$")
    }
}