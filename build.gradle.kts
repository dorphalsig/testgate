plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    jacoco
}

gradlePlugin {
    plugins {
        register("testGate") {
            id = "com.supernova.testgate"
            implementationClass = "com.supernova.testgate.convention.TestGateConventionPlugin"
        }
    }
}


group = "com.supernova"
version = "1.0.2"

dependencies {
    implementation(gradleApi())
    implementation(gradleTestKit())
    implementation(libs.moshi.kotlin)
    // We reference these plugin classes in code; keep them compileOnly so consumers don’t get them transitively
    compileOnly(libs.gradle)                // com.android.tools.build:gradle:<agp version from catalog>
    compileOnly(libs.detekt.gradle.plugin)  // detekt-gradle-plugin:<detekt version from catalog>
    // detekt custom rules API for our provider/rules
    compileOnly(libs.detekt.api)

    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(kotlin("gradle-plugin", "1.9.24"))

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf(
                "com.supernova.testgate.TestGatePlugin",
                "com.supernova.testgate.convention.TestGateConventionPlugin"
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY")}")
            credentials {
                username = "VALUE_UNCHECKED"
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
