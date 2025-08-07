package com.supernova.testgate

import org.gradle.api.Project
import javax.xml.parsers.DocumentBuilderFactory

data class TestFailureDetail(
    val className: String,
    val testName: String,
    val message: String,
    val stackTrace: String
)

class AssertionDetailsExtractor(private val project: Project) {
    fun extract(): List<TestFailureDetail> {
        val failures = mutableListOf<TestFailureDetail>()
        val resultsDir = project.buildFile.parentFile.resolve("build/test-results/test")
        if (!resultsDir.exists()) return failures
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        resultsDir.listFiles { f -> f.extension == "xml" }?.forEach { xml ->
            val doc = builder.parse(xml)
            val cases = doc.getElementsByTagName("testcase")
            for (i in 0 until cases.length) {
                val node = cases.item(i)
                val attributes = node.attributes
                val className = attributes.getNamedItem("classname")?.nodeValue ?: "Unknown"
                val testName = attributes.getNamedItem("name")?.nodeValue ?: "Unknown"
                val failuresNodes = node.childNodes
                for (j in 0 until failuresNodes.length) {
                    val child = failuresNodes.item(j)
                    if (child.nodeName == "failure") {
                        val msg = child.attributes.getNamedItem("message")?.nodeValue ?: ""
                        val stack = child.textContent ?: ""
                        failures += TestFailureDetail(className, testName, msg, stack)
                    }
                }
            }
        }
        return failures
    }
}