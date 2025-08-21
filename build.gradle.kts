plugins {
    `kotlin-dsl`          // uses Gradle’s embedded Kotlin; no separate kotlin plugin version needed
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("testGate") {
            id = "com.supernova.testgate"
            implementationClass = "com.supernova.testgate.TestGatePlugin"
        }
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
    testImplementation(libs.junit5)
    testImplementation(libs.mockk)
}
