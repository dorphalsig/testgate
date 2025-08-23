plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

gradlePlugin {
    plugins {
        register("testGate") {
            id = "com.supernova.testgate"
            implementationClass = "com.supernova.testgate.TestGatePlugin"
        }
    }
}


group = "com.supernova"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(gradleApi())
    implementation(gradleTestKit())
    implementation(libs.moshi.kotlin)
    // We reference these plugin classes in code; keep them compileOnly so consumers don’t get them transitively
    compileOnly(libs.gradle)                // com.android.tools.build:gradle:<agp version from catalog>
    compileOnly(libs.detekt.gradle.plugin)  // detekt-gradle-plugin:<detekt version from catalog>

    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
