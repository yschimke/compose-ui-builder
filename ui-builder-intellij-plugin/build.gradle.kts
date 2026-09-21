plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.intellij.platform)
}

ktfmt { googleStyle() }

kotlin { jvmToolchain(libs.versions.java.ui.builder.get().toInt()) }

repositories {
  google()
  mavenCentral()
  maven("https://raw.githubusercontent.com/yschimke/wear-m3-catalog/wear-compose-cmp-maven/") {
    content { includeGroup("ee.schimke.wearcmp") }
  }
  intellijPlatform { defaultRepositories() }
}

dependencies {
  implementation(project(":ui-builder-desktop")) {
    // Jewel and the IDE own these classes. Shipping the desktop host's copies creates two Compose
    // runtimes and two native Skiko loaders in one process; keep only libraries above that layer.
    exclude(group = "androidx.compose.runtime")
    exclude(group = "org.jetbrains.compose.animation")
    exclude(group = "org.jetbrains.compose.foundation")
    exclude(group = "org.jetbrains.compose.runtime")
    exclude(group = "org.jetbrains.compose.ui")
    exclude(group = "org.jetbrains.skiko")
  }
  // Compile the @Composable tool-window lambda against the same API the platform bundles. Keeping
  // this compile-only avoids shipping a second Compose runtime inside the plugin.
  compileOnly("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}")

  intellijPlatform {
    // IntelliJ uses one unified distribution from the 253 platform onward.
    intellijIdea("2026.2.3")
    bundledModule("intellij.platform.jewel.foundation")
    bundledModule("intellij.platform.jewel.ui")
    bundledModule("intellij.platform.jewel.ideLafBridge")
    bundledModule("intellij.libraries.compose.foundation.desktop")
    bundledModule("intellij.libraries.skiko")
  }
}

intellijPlatform {
  pluginConfiguration {
    name = "Compose UI Builder POC"
    version = "0.0.1"
    ideaVersion {
      sinceBuild = "262"
      untilBuild = provider { null }
    }
  }
}
