import org.gradle.kotlin.dsl.mavenCentral
import org.gradle.kotlin.dsl.repositories

pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
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
