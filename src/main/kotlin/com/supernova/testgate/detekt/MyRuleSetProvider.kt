package com.supernova.testgate.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

class TestGateRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "testgate"
    override fun instance(config: Config): RuleSet =
        RuleSet(ruleSetId, listOf(RequireHarnessAnnotationOnTests(config)))
}
