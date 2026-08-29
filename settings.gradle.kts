pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  // Kotlin/Wasm adds the Node distribution as an Ivy repository when its setup task is realized.
  // The build scripts declare no repositories; project preference exists solely for that
  // plugin-owned toolchain repository.
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "compose-preview-server"

include(":server")
include(":usage-source-psi")
include(":wasm-ui")
include(":native-catalog-m3")
