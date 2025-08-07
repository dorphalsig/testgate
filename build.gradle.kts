plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Moshi for JSON
    implementation(libs.moshi)
    // XML parsing and Java Compiler API are on the JDK
}