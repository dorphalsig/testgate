package com.supernova.testgate

import org.gradle.api.Project

data class ImportViolation(
    val filePath: String,
    val imported: String,
    val violationType: String
)

class ImportScanner(private val project: Project) {
    private val whitelist: List<String> by lazy {
        (project.findProperty("importWhitelist") as? String)
            ?.split(',')?.map { it.trim() } ?: emptyList()
    }

    private val bannedPatterns: List<String> by lazy {
        (project.findProperty("bannedImportPatterns") as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private val allowBannedImportClasses: Set<String> by lazy {
        (project.findProperty("allowBannedImportClasses") as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()
    }

    fun scan(): List<ImportViolation> {
        val violations = mutableListOf<ImportViolation>()

        project.fileTree("src")
            .matching { include("**/*.kt", "**/*.java") }
            .forEach { file ->
                val relativePath = file.relativeTo(project.projectDir).path
                val fqcn = extractFqcn(file)
                val fileAllowed = allowBannedImportClasses.any { fqcn.startsWith(it) }

                file.readLines().forEach { line ->
                    if (line.trim().startsWith("import ")) {
                        val imp = line.removePrefix("import ").trim().removeSuffix(";")

                        // Check banned patterns first (unless file FQCN is allowed)
                        if (!fileAllowed && bannedPatterns.isNotEmpty()) {
                            bannedPatterns.forEach { pattern ->
                                if (imp.startsWith(pattern)) {
                                    violations += ImportViolation(relativePath, imp, "BANNED_IMPORT")
                                }
                            }
                        }

                        // Check whitelist (if defined and not already flagged as banned)
                        if (whitelist.isNotEmpty() &&
                            whitelist.none { imp.startsWith(it) } &&
                            violations.none { it.filePath == relativePath && it.imported == imp }) {
                            violations += ImportViolation(relativePath, imp, "NOT_WHITELISTED")
                        }
                    }
                }
            }
        return violations
    }

    private fun extractFqcn(file: java.io.File): String {
        val content = file.readText()
        val pkg = Regex("""^\s*package\s+([\w.]+)""").find(content)?.groupValues?.get(1)
        val cls = file.nameWithoutExtension
        return if (pkg != null) "$pkg.$cls" else cls
    }
}