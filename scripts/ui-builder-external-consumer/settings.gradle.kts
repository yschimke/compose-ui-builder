import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement { repositories.clear() }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    maven { url = uri(providers.gradleProperty("gateRepository").get()) }
    mavenCentral()
  }
}

rootProject.name = "ui-builder-external-consumer"
