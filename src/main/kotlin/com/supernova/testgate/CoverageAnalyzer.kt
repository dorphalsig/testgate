package com.supernova.testgate


import org.gradle.api.Project

import javax.xml.parsers.DocumentBuilderFactory


class CoverageAnalyzer(private val project: Project) {

    fun analyze(): Double {

        val xml =
            project.projectDir.resolve("build/reports/jacoco/jacocoMergedReport/jacocoMergedReport.xml")
        if (!xml.exists()) return 0.0
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(xml)
        val counters = doc.getElementsByTagName("counter")
        var covered = 0L
        var missed = 0L
        for (i in 0 until counters.length) {
            val node = counters.item(i)
            if (node.attributes.getNamedItem("type").nodeValue == "INSTRUCTION") {
                covered = node.attributes.getNamedItem("covered").nodeValue.toLong()
                missed = node.attributes.getNamedItem("missed").nodeValue.toLong()
                break
            }
        }
        return if (covered + missed == 0L) 0.0 else covered.toDouble() / (covered + missed) * 100
    }
}
