package com.supernova.testgate

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.Project

object ReportCoordinator {

    fun handleBuildFinished(root: Project, allResults: List<TaskResult>) {
        // group by project name (first segment after colon)
        val byProject = allResults.groupBy { fullPath ->
            fullPath.taskPath.trim(':').substringBefore(':')
        }

        // build per-project JSON
        val rootMap = mutableMapOf<String, Any>()
        val anyFailures = mutableListOf<String>()

        byProject.forEach { (projName, results) ->
            val proj = root.findProject(projName) ?: return@forEach
            val agg = TaskResultAggregator(results)
            val imports = ImportScanner(proj).scan()
            val assertions = AssertionDetailsExtractor(proj).extract()
            val cov = CoverageAnalyzer(proj).analyze()
            val det = DetektAnalyzer(proj).analyze()

            val validators = TestValidators(proj)
            val testViolations = listOf(
                validators.validateCoroutineTests(),
                validators.validateTestStructure(),
                validators.validateIgnoredTests()
            ).flatten()

            val writer = ReportWriter(proj, agg, imports, assertions, cov, det, testViolations)
            val projJson = writer.generateReportMap()
            rootMap[projName] = projJson

            if (agg.compileErrors().isNotEmpty()
                || agg.testFailures().isNotEmpty()
                || testViolations.isNotEmpty()
                || (projJson.contains("testThresholdExceeded"))
                || (projJson.contains("coverageThresholdExceeded"))
                || (projJson.contains("detektThresholdExceeded"))
            ) {
                anyFailures += projName
            }
        }

        // write overall file
        val outputFile = root.buildDir.resolve("testgate-report.json")
        outputFile.parentFile.mkdirs()
        val finalJson = JsonOutput.prettyPrint(JsonOutput.toJson(rootMap))
        outputFile.writeText(finalJson)

        // status line
        if (anyFailures.isEmpty())
            root.logger.lifecycle("TestGate: ✅ SUCCESS: ALL TESTS PASSED")
        else {
            // upload
            val pasteUrl = PasteRsUploader.upload(finalJson)
            val msg =
                """❌ FAIL: TestGate detected issues in projects: ${anyFailures.joinToString()} 
                    full Report: ${outputFile.absolutePath}
                    Paste.rs link: ${pasteUrl}.json
                """.trimIndent()
            throw GradleException(msg)
        }
    }
}