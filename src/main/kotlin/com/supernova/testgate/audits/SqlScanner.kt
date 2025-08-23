package com.supernova.testgate.audits

/**
 * Stateless scanners & small regex helpers. Keep methods short (≤30 LOC).
 */
internal object SqlScanner {

    private val reRawQuery = Regex("""(?m)^\s*@RawQuery\b""")
    private val reSupportSQLiteQuery = Regex("""\bSupportSQLiteQuery\b""")
    private val reFts4 = Regex("""\B@Fts4\b""", RegexOption.IGNORE_CASE)
    private val reFts5 = Regex("""\B@Fts5\b""", RegexOption.IGNORE_CASE)

    private val reQuery =
        Regex("""@Query\s*\(\s*(?:value\s*=\s*)?(""" + "\"\"\"" + """[\s\S]*?""" + "\"\"\"" + """|\"([^\"\\]|\\.)*\")\s*\)""")

    private val reComplex =
        Regex("""\b(JOIN|UNION|WITH|CREATE|ALTER|INSERT|UPDATE|DELETE)\b""", RegexOption.IGNORE_CASE)

    private val reFromRail =
        Regex("""\bFROM\s+([`"]?[A-Za-z0-9_."`]*RailEntry[A-Za-z0-9_."`]*)""", RegexOption.IGNORE_CASE)

    private val reOrderByPos =
        Regex("""\bORDER\s+BY\s+[^;]*\bposition\b""", RegexOption.IGNORE_CASE)

    private val reOrderByPopularity =
        Regex("""\bORDER\s+BY\s+[^;]*\bpopularity\b""", RegexOption.IGNORE_CASE)

    data class FtsFlags(val seenAny: Boolean, val seenFts4: Boolean)

    data class QueryHit(val sql: String, val line: Int)

    // ---------- Public API ----------

    fun extractQueries(source: String): List<QueryHit> {
        val lineIdx = buildLineIndex(source)
        return reQuery.findAll(source).map { m ->
            val raw = m.groupValues[1]
            val sql = unwrapQuotes(raw).trim()
            QueryHit(sql, lineOf(lineIdx, m.range.first))
        }.toList()
    }

    fun findComplexQueryIssues(queries: List<QueryHit>, filePath: String): List<Finding> {
        val out = ArrayList<Finding>()
        for (q in queries) {
            if (reComplex.containsMatchIn(q.sql)) {
                out += finding(
                    type = "ComplexQueryInDao",
                    file = filePath,
                    line = q.line,
                    msg = "Complex SQL keyword found in @Query (JOIN/UNION/WITH/DDL/DML are forbidden)."
                )
            }
        }
        return out
    }

    fun findRailsGuardIssues(queries: List<QueryHit>, filePath: String): List<Finding> {
        val out = ArrayList<Finding>()
        for (q in queries) {
            val selectsRail = reFromRail.containsMatchIn(q.sql)
            if (selectsRail && !reOrderByPos.containsMatchIn(q.sql)) {
                out += finding(
                    type = "RailsOrderGuard",
                    file = filePath,
                    line = q.line,
                    msg = "Queries selecting from RailEntry must ORDER BY position."
                )
            }
            if (reFromRail.containsMatchIn(q.sql) && reOrderByPopularity.containsMatchIn(q.sql)) {
                out += finding(
                    type = "RailsOrderGuard",
                    file = filePath,
                    line = q.line,
                    msg = "ORDER BY popularity is forbidden for RailEntry queries."
                )
            }
        }
        return out
    }

    fun findRawQueryIssues(source: String, filePath: String): List<Finding> {
        val lineIdx = buildLineIndex(source)
        return reRawQuery.findAll(source).map {
            finding("RawQueryUsage", filePath, lineOf(lineIdx, it.range.first), "@RawQuery is forbidden (use Room-safe APIs).")
        }.toList()
    }

    fun findSupportSQLiteQueryIssues(source: String, filePath: String): List<Finding> {
        val lineIdx = buildLineIndex(source)
        return reSupportSQLiteQuery.findAll(source).map {
            finding("SupportSQLiteQueryUsage", filePath, lineOf(lineIdx, it.range.first), "SupportSQLiteQuery usage is forbidden.")
        }.toList()
    }

    fun findFts5Issues(source: String, filePath: String): List<Finding> {
        val lineIdx = buildLineIndex(source)
        return reFts5.findAll(source).map {
            finding("Fts5Used", filePath, lineOf(lineIdx, it.range.first), "@Fts5 is banned; use @Fts4.")
        }.toList()
    }

    fun scanFtsFlags(source: String): FtsFlags {
        val seen4 = reFts4.containsMatchIn(source)
        val seen5 = reFts5.containsMatchIn(source)
        return FtsFlags(seenAny = seen4 || seen5, seenFts4 = seen4)
    }

    fun finalizeFtsFindings(sawAnyFts: Boolean, sawFts4: Boolean): List<Finding> {
        val out = ArrayList<Finding>()
        if (!sawAnyFts) return out
        if (!sawFts4) {
            out += finding("FtsMissingFts4", null, null, "FTS is used in this module but no @Fts4 entity exists.")
        }
        return out
    }

    // ---------- Helpers ----------

    private fun buildLineIndex(text: String): IntArray {
        val lines = ArrayList<Int>()
        lines.add(0)
        for (i in text.indices) if (text[i] == '\n') lines.add(i + 1)
        return lines.toIntArray()
    }

    private fun lineOf(index: IntArray, offset: Int): Int {
        var lo = 0
        var hi = index.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = index[mid]
            if (v <= offset) lo = mid + 1 else hi = mid - 1
        }
        return hi + 1 // 1-based
    }

    private fun unwrapQuotes(q: String): String {
        return if (q.startsWith("\"\"\"")) q.removePrefix("\"\"\"").removeSuffix("\"\"\"")
        else q.removePrefix("\"").removeSuffix("\"").replace("\\\"", "\"").replace("\\n", "\n")
    }

    private fun finding(type: String, file: String?, line: Int?, msg: String) = Finding(
        type = type,
        filePath = file,
        line = line,
        severity = "error",
        message = msg,
        stacktrace = emptyList()
    )
}
