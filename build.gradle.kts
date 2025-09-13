plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    jacoco
}

gradlePlugin {
    gradlePlugin {
        plugins {
            register("testGateConventions") {
                id = "com.supernova.testgate.conventions"
                implementationClass = "com.supernova.testgate.conventions.TestGateConventionsPlugin"
                displayName = "TestGate Conventions Plugin"
                description = "Applies & configures Detekt, JaCoCo, JUnit 5 (Android/JVM) for TestGate audits."
            }
        }
    }
}


group = "com.supernova"
version = "1.0.3"

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
    testImplementation(libs.detekt.gradle.plugin)

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        dependsOn("pluginUnderTestMetadata")
    }
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
