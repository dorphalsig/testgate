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
                id = "com.supernova.testgate"
                implementationClass = "com.supernova.testgate.conventions.TestGateConventionsPlugin"
                displayName = "TestGate Conventions Plugin"
                description = "Applies & configures Detekt, JaCoCo, JUnit 5 (Android/JVM) for TestGate audits."
            }
        }
    }
}


group = "com.supernova"
version = "1.0.4"

dependencies {
    implementation(gradleApi())
    implementation(gradleTestKit())
    implementation(libs.moshi.kotlin)
    // We compile against AGP without bundling it; Detekt & Android JUnit5 ship for consumers
    compileOnly(libs.gradle) // com.android.tools.build:gradle:<agp version from catalog>
    implementation(libs.detekt.gradle.plugin) // detekt-gradle-plugin:<detekt version from catalog>
    implementation(libs.junit5.android.plugin) // de.mannodermaus:android-junit5
    // detekt custom rules API for our provider/rules
    compileOnly(libs.detekt.api)

    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(kotlin("gradle-plugin", "1.9.24"))
    // runtime plugin deps come transitively from implementation

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
