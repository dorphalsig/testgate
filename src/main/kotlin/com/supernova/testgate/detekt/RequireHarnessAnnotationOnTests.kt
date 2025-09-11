package com.supernova.testgate.detekt

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Flags any test class (scoped via includes to src/test or src/androidTest)
 * that contains @Test methods but lacks @UseTestHarness or @PureUnitTest.
 * No type resolution required.
 */
class RequireHarnessAnnotationOnTests(config: Config) : Rule(config) {

    override val issue = Issue(
        id = "RequireHarnessAnnotationOnTests",
        severity = Severity.CodeSmell,
        description = "Classes with @Test methods must be annotated with @UseTestHarness or @PureUnitTest.",
        debt = Debt.FIVE_MINS
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isAnnotation() || klass.isInterface()) return

        val hasTestMethod = klass.declarations
            .filterIsInstance<KtNamedFunction>()
            .any { fn -> fn.annotationEntries.any { it.shortName?.asString() == "Test" } }

        if (!hasTestMethod) return

        val hasHarness = klass.annotationEntries.any { ann ->
            when (ann.shortName?.asString()) {
                "UseTestHarness", "PureUnitTest" -> true
                else -> false
            }
        }

        if (!hasHarness) {
            report(
                CodeSmell(
                    issue, Entity.atName(klass),
                    "Add @UseTestHarness or @PureUnitTest to this test class."
                )
            )
        }
    }
}
