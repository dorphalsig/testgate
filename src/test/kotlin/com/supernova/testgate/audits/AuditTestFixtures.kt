package com.supernova.testgate.audits

import java.io.File

data class CheckstyleEntry(val severity: String, val line: Int, val message: String)

fun detektCheckstyleXml(filePath: String, entries: List<CheckstyleEntry>): String = buildString {
    append(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <checkstyle version="8.0">
          <file name="$filePath">
        """.trimIndent()
    )
    entries.forEach { e ->
        // Real detekt writes source="detekt.RuleId" (e.g., detekt.ForbiddenImport).
        // Extract the rule id from a bracketed prefix in the message when present.
        val ruleId = Regex("""\[(?:detekt\.)?([A-Za-z0-9_.-]+)]""")
            .find(e.message)
            ?.groupValues?.get(1)
        val source = if (ruleId != null) "detekt.$ruleId" else "detekt"
        append(
            "\n    <error severity=\"${e.severity}\" line=\"${e.line}\" message=\"${e.message}\" source=\"$source\"/>"
        )
    }
    append("\n  </file>\n</checkstyle>")
}


data class LintEntry(val id: String, val severity: String, val file: String, val line: Int)

fun lintXml(entries: List<LintEntry>): String = buildString {
    append(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <issues format="5" by="lint">
        """.trimIndent()
    )
    entries.forEach { e ->
        append("\n  <issue id=\"${e.id}\" severity=\"${e.severity}\" message=\"msg\">")
        append("\n    <location file=\"${e.file}\" line=\"${e.line}\"/>")
        append("\n  </issue>")
    }
    append("\n</issues>")
}

fun createSrcFiles(root: File, count: Int) {
    val base = File(root, "src/test/java/com/example").apply { mkdirs() }
    repeat(count) { idx -> File(base, "F$idx.java").writeText("class F$idx{}") }
}
