package com.supernova.testgate
import org.gradle.api.Project
import javax.xml.parsers.DocumentBuilderFactory
class DetektAnalyzer(private val project: Project) {
    fun analyze(): Double {
        val xml = project.buildFile.parentFile.resolve("build/reports/detekt/detekt.xml")
        if (!xml.exists()) return 0.0
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(xml)
        val issues = doc.getElementsByTagName("issue").length
        // total files scanned = <file> elements
        val files = doc.getElementsByTagName("file").length
        return if (files == 0) 0.0 else issues.toDouble() / files * 100
    }
}
