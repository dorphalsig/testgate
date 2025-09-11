### TestGate — Audits, Configuration, and Usage

TestGate is a Gradle plugin that runs a suite of audits after your usual build and test tasks. Each audit parses existing tool output or scans your sources, aggregates findings, and reports a single PASS/FAIL per module. All audit results are aggregated into a JSON report and the build fails if any audit fails.

- Aggregated report (always written): `build/reports/testgate-results.json`
- On failure, the build error message includes failed audit names, the local JSON path, and (optionally) an online URL.

---

### What’s included (Audits)

Below are the audits currently wired by the plugin, their task names, inputs, and pass/fail rules.

1) Compilation audit — `CompilationAudit`
- Task wiring: auto-wired around Kotlin/Javac/KSP/KAPT compile tasks (stderr capture) via a hidden task `compilationAudit` finalized after compile tasks.
- Input: compiler stderr collected during `compileDebugKotlin`, `compileDebugJavaWithJavac`, `kaptDebug*`, `kspDebug*`.
- Rule: any compiler error finding → FAIL (tolerance 0).

2) Detekt audit — `Detekt`
- Task: `detektAudit` (runs after `detekt`).
- Input: `build/reports/detekt/detekt.xml`.
- Counts only severity=error. Path-based whitelist supported. Hard-fail list of rule IDs supported.
- Pass rule: `softErrors/files <= tolerancePercent%`, unless any finding’s `ruleId` is in `hardFailRuleIds` (then FAIL).

3) Android Lint audit — `AndroidLintAudit`
- Task: `lintDebugAudit` (runs after `lintDebug`).
- Input: `build/reports/lint-results-debug.xml` (format="6"). Considers severity Error/Fatal only.
- Pass rule: `errors/files <= tolerancePercent%`.

4) Harness reuse & isolation — `HarnessReuseIsolationAudit`
- Task: `harnessReuseAudit` (finalized after compile tasks).
- Rule A (src/test/**): test files in `com.supernova.{data|sync|ui}..*` must import ≥1 area-specific helper (cross-layer helpers alone aren’t sufficient). Files with default package are skipped. Whitelist can skip files.
- Rule B (project-wide): no local classes that clone canonical helper simple names outside `com.supernova.testing..*`. Whitelist can skip FQCNs.
- Pass rule: tolerance 0 (any finding fails).

5) SQL / FTS safety — `auditsSqlFts`
- Task: `auditsSqlFts` (finalized after compile tasks).
- Scans Kotlin/Java under `src/**` for:
  - Ban `@RawQuery` and `SupportSQLiteQuery` (whitelist-exempt for raw, support query? See notes below).
  - Ban complex SQL in `@Query` (JOIN|UNION|WITH|CREATE|ALTER|INSERT|UPDATE|DELETE) — whitelistable.
  - FTS guard: `@Fts5` banned; if any FTS is used, `@Fts4` required.
  - “Rails guard”: on selects from `RailEntry`, require `ORDER BY position` and forbid `ORDER BY popularity`.
- Pass rule: `findings * 100 <= tolerancePercent * scannedFiles`.

6) Structure audit — `auditsStructure`
- Task: `structureAudit` (finalized after compile tasks).
- Ensures:
  - `src/sharedTest/**` is banned.
  - JVM tests live only under `src/test/kotlin/**` (no Java under `src/test/**`, no Kotlin outside `src/test/kotlin/**`).
  - If tests/resources exist under `src/test/**`, require `testImplementation(project(":testing-harness"))` in the module.
  - Android instrumented tests under `src/androidTest/**` may only import allow-listed FQCNs (scope-ban). Offending file ratio is compared to a tolerance.
- Pass rule:
  - Any structural finding (above) → FAIL (structural tolerance is 0).
  - Instrumented scope: percentage of offending `androidTest` files must be `<= instrumentedTolerancePercent`.

7) Test stack policy (JVM) — `auditsStack`
- Task: `testStackPolicyAudit` (finalized after compile tasks).
- Scope: `src/test/kotlin/**/*.kt`
- Fails on:
  - Banned imports: `org.junit.Test`, and any starting with `androidx.test.*`, `org.robolectric.*`, `androidx.test.espresso.*`, `androidx.compose.ui.test.*`.
  - Banned annotations: `@Ignore` and all JUnit 5 `@Disabled*` variants.
  - Coroutines misuse: `runBlocking(`, `Thread.sleep(`, scheduler APIs without `runTest(`.
  - Uses `Dispatchers.Main` or `viewModelScope` but no `MainDispatcherRule`.
- Path whitelist available. Tolerance 0.

8) Fixtures audit — `auditsFixtures`
- Task: `runFixturesAudit` (finalized after compile tasks).
- Looks for `src/test/resources/**/*.json`.
- Presence: require ≥ 1 JSON fixture unless the module path is whitelisted.
- Size window warnings: `minBytes <= size <= maxBytes`; oversize/too-small counted against tolerance.
- Pass rule: if presence satisfied and `(TooSmall+Oversize)/total <= tolerancePercent`.

9) Tests audit (JVM unit tests) — `auditsTests`
- Task: `testGateAuditsTests` (runs after each unit test task like `testDebugUnitTest` or `test`).
- Input: JUnit XML dirs emitted by Gradle (`testTask.reports.junitXml.outputLocation`).
- Executed tests exclude skipped/disabled/aborted. Failures include `<failure/>` and `<error/>`.
- Pass rule: `failed/executed <= tolerancePercent%`. Whitelist can exempt `Class#method` or class via glob.

10) Tests audit (Instrumented Android tests)
- Task: `testGateInstrumentedAuditsTests` (finalized after any `connected<Variant>AndroidTest` and `instrumented<Variant>Test` aliases).
- Input: `build/outputs/androidTest-results/connected`.
- Same rules as JVM Tests audit; distinct config keys.

11) Coverage (branches) — `Coverage (branches)`
- Task: `coverageBranchesAudit` (finalized after any JaCoCo task; expects Android unit test XML at `build/reports/jacoco/testDebugUnitTestReport/testDebugUnitTestReport.xml`).
- Aggregates class-level BRANCH counters from JaCoCo XML.
- Module branch coverage percent (rounded to 1 decimal) is returned as `findingCount`.
- On FAIL, emits one finding per non-whitelisted class below threshold; otherwise no findings.
- Pass rule: module branch coverage `>= thresholdPercent`.

---

### Configuration (Gradle properties)

Set these in `gradle.properties` (root or module), or pass via `-Pkey=value`. CSV lists accept comma-separated values; globs use `*`, `**`, `?`.

- Detekt
  - `testgate.detekt.tolerancePercent` (Int, default 10)
  - `testgate.detekt.whitelist.patterns` (CSV of path globs relative to module, e.g. `**/legacy/**, **/generated/**`)
  - `testgate.detekt.hardFailRuleIds` (CSV of rule IDs, e.g. `ForbiddenImport,ForbiddenMethodCall,RequireHarnessAnnotationOnTests`)

- Android Lint
  - `testgate.lint.tolerancePercent` (Int, default 10)
  - `testgate.lint.whitelist.patterns` (CSV of path globs)

- SQL / FTS
  - `testgate.sqlFts.tolerancePercent` (Int, default 0)
  - `testgate.sqlFts.whitelist` (CSV of path globs)

- Structure
  - `testgate.structureAudit.instrumentedAllowList` (CSV of FQCN/package allow-list globs, e.g. `com.supernova.data.db.fts.**, com.supernova.security.securestorage.**`)
  - Tolerance for instrumented scope is the constructor default (0 unless changed in code). Structural tolerance is always 0.

- Test Stack Policy
  - `testgate.stack.allowlist.files` (CSV of path globs relative to moduleDir; whitelists files from policy checks)
  - Note: a property `testgate.stack.mainDispatcherRules` is documented in comments but not read in code — `MainDispatcherRule` check is built-in.

- Fixtures
  - `testgate.fixtures.tolerancePercent` (Int, default 10)
  - `testgate.fixtures.minBytes` (Int, default 256)
  - `testgate.fixtures.maxBytes` (Int, default 8192)
  - `testgate.fixtures.whitelist.patterns` (CSV of path globs; if module path matches any, presence rule is skipped)

- Tests (JVM)
  - `testgate.tests.tolerancePercent` (Int, default 10)
  - `testgate.tests.whitelist.patterns` (CSV of patterns; matches `Class#method` or class via glob, dots or slashes supported)

- Tests (Instrumented)
  - `testgate.instrumentedTests.tolerancePercent` (Int, default 10)
  - `testgate.instrumentedTests.whitelist.patterns` (CSV of patterns; same semantics as JVM)

- Coverage (branches)
  - `testgate.coverage.branches.minPercent` (Int, default 70)
  - `testgate.coverage.whitelist.patterns` (CSV of FQCN or path globs; whitelisted classes are excluded from coverage check)

Whitelist semantics (shared)
- Path-style patterns support `*` (segment), `**` (any depth), `?` (single char). Leading `/` anchors to start; otherwise matches anywhere.
- FQCNs are matched as dot-form or slash-form; patterns apply to both forms.

Examples (gradle.properties)
```
# Detekt
testgate.detekt.tolerancePercent=10
testgate.detekt.whitelist.patterns=**/generated/**, **/legacy/**
testgate.detekt.hardFailRuleIds=ForbiddenImport,RequireHarnessAnnotationOnTests

# Lint
testgate.lint.tolerancePercent=5
testgate.lint.whitelist.patterns=**/sample/**

# SQL/FTS
testgate.sqlFts.tolerancePercent=0
testgate.sqlFts.whitelist=**/debug/**

# Structure
testgate.structureAudit.instrumentedAllowList=com.supernova.data.db.fts.**, com.supernova.security.securestorage.**

# Test stack policy
testgate.stack.allowlist.files=**/legacy/**

# Fixtures
testgate.fixtures.tolerancePercent=20
testgate.fixtures.minBytes=128
testgate.fixtures.maxBytes=16384
testgate.fixtures.whitelist.patterns=**/no-fixtures-module/**

# Tests (JVM)
testgate.tests.tolerancePercent=5
testgate.tests.whitelist.patterns=com.supernova.legacy.**, **/ExperimentalTest.kt

# Tests (Instrumented)
testgate.instrumentedTests.tolerancePercent=10
testgate.instrumentedTests.whitelist.patterns=com.supernova.legacy.android.**

# Coverage (branches)
testgate.coverage.branches.minPercent=75
testgate.coverage.whitelist.patterns=com.supernova.experimental.**
```

---

### How the plugin wires tasks

The core plugin `TestGatePlugin` registers hidden audit tasks and wires them with `finalizedBy` so audits run after their producing tasks:
- Compile → `compilationAudit` (with stderr capture registered/unregistered around the compile task)
- Detekt → `detektAudit`
- Android Lint → `lintDebugAudit`
- Compile → `harnessReuseAudit`, `auditsSqlFts`, `structureAudit`, `testStackPolicyAudit`, `runFixturesAudit`
- Unit tests (`test`, `testDebugUnitTest`, etc.) → `testGateAuditsTests`
- Instrumented tests (`connected<Variant>AndroidTest`, `instrumented<Variant>Test`) → `testGateInstrumentedAuditsTests`
- Any JaCoCo report task → `coverageBranchesAudit` (expects XML at `build/reports/jacoco/testDebugUnitTestReport/testDebugUnitTestReport.xml` for Android unit tests)

Note: For JVM/KMP modules, coverage piggybacks on `jacocoTestReport`. For Android, the convention plugin config emits per-variant reports.

---

### Usage

You can use the low-level plugin directly, or apply the provided convention plugin that also enables and configures Detekt, Lint, Jacoco, and some Android test conveniences.

Option A) Apply the convention plugin (recommended if available)
- The convention plugin class is `com.supernova.testgate.convention.TestGateConventionPlugin`. If published with an id, apply it by id; otherwise, apply by type in a build logic module.
- What it does:
  - Applies `TestGatePlugin`
  - Applies and configures Detekt (XML report enabled, `ignoreFailures=true`), Android Lint (XML on, non-fatal), Jacoco
  - Wires custom detekt rules from the plugin jar or `tools/testgate-detekt.jar` if present
  - Configures Android instrumentation aliases and JUnit 5 for instrumented tests
  - Exposes extra properties: `isAndroid`, `currentTestVariant`, `executedTestTasks`

Example (build.gradle.kts in module)
```
plugins {
    // If you have a published plugin id, prefer that:
    // id("com.supernova.testgate.convention")

    // Or apply by type if you include this plugin in build-logic
    id("org.jetbrains.kotlin.android") version "<...>"
}

// If applying by type from included build logic:
// plugins.apply(com.supernova.testgate.convention.TestGateConventionPlugin::class.java)
```

Option B) Apply the core plugin
```
plugins {
    // id("com.supernova.testgate") // if published
}

// Or, if you include the plugin sources in build-logic, apply by type:
// plugins.apply(com.supernova.testgate.TestGatePlugin::class.java)
```

Prerequisites
- Detekt task emits XML at `build/reports/detekt/detekt.xml` (the convention plugin configures this).
- Android Lint emits `lint-results-<variant>.xml` (convention plugin configures this for debug as `lint-results-debug.xml`).
- JaCoCo XML reports are generated for unit tests (the convention plugin sets this up for Android Debug and piggybacks `jacocoTestReport` for JVM/KMP).

Running
- Run your usual tasks; audits are wired automatically:
  - `./gradlew :app:assembleDebug` → compile-time audits run after compilation.
  - `./gradlew :app:detekt` → Detekt audit runs after Detekt.
  - `./gradlew :app:lintDebug` → Lint audit runs after Lint.
  - `./gradlew :app:testDebugUnitTest` → Tests audit runs after tests.
  - `./gradlew :app:connectedDebugAndroidTest` → Instrumented Tests audit runs after tests.
  - `./gradlew :app:jacocoTestReport` (or Android per-variant) → Coverage audit runs afterwards.

Fail behavior and report
- On any audit failure, the build throws with a message like:
```
Build Failed. The following audits failed: :module:AuditName, ...
Local json: /build/reports/testgate-results.json
Online json: <url-or-unavailable>
```
- The JSON includes every audit’s findings (type, file, line, severity, message, stack traces if present), tolerance, finding count, and status.

Optional upload
- The report service may upload a pretty JSON to an online paste service. Upload failures do not fail the build; the message will show `Online json: unavailable`.

---

### Whitelist patterns — cheatsheet
- Paths: `**/generated/**`, `src/test/**`, `**/*.kt`
- FQCNs or symbols: `com.supernova.legacy..*`, `com.supernova.data.db.fts.**`
- Anchoring: start with `/` to anchor to path start (e.g., `/src/androidTest/**`).
- Dots and slashes are normalized for matching; `com.example.Foo#bar` symbols are also supported in some audits (tests audit).

---

### Known defaults and opinions
- Many tolerances default to 10% (Detekt, Lint, Tests). Structure findings are hard-fail. Harness and Stack audits are hard-fail.
- Instrumented-scope allow-list in Structure audit defaults to:
  - `com.supernova.data.db.fts.**`
  - `com.supernova.security.securestorage.**`

---

### Troubleshooting
- “Report not found” failures mean the producing tool didn’t run or didn’t write XML where expected. Ensure the convention plugin (or your build) enables XML output for Detekt, Lint, and JaCoCo.
- If coverage audit can’t find `testDebugUnitTestReport.xml`, make sure you executed the per-variant Jacoco report task the convention plugin creates for Android, or adjust wiring if your variant name differs.
- Use whitelist patterns conservatively and document why exclusions exist.

---

### Internals and extension points
- Extension: `testGate { onAuditResult }` exposes a callback audits use to enqueue results into a shared build service.
- Aggregation: `TestGateReportService` collects all `AuditResult` objects, writes pretty JSON, optionally uploads, and then decides build pass/fail.
- Audit model: `AuditResult { module, name, findings[], tolerance, findingCount, status }` and `Finding { type, filePath?, line?, severity?, message, stacktrace[] }`.
- Utils: `WhitelistMatcher` implements glob matching for paths and FQCNs; `scanSourceFiles` helps tolerance math.

If you need a quick start or a tailored setup (e.g., multi-variant Android or KMP specifics), you can adapt the convention plugin to your build logic, then tune the Gradle properties above to your policy.
