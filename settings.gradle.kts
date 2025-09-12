pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins{
        id("io.gitlab.arturbosch.detekt")     version "1.23.8"   // latest stable on Portal
        id("de.mannodermaus.android-junit5")  version "1.13.4.0" // latest release
    }

}


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
}
rootProject.name = "testgate"
