import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.intellij.platform)
}

ktfmt { googleStyle() }

kotlin { jvmToolchain(libs.versions.java.ui.builder.get().toInt()) }

val integrationTestSourceSet =
  sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }

configurations.getByName("integrationTestImplementation") {
  extendsFrom(configurations.testImplementation.get())
}

// Keep the plugin descriptor and release asset on the repository's version line. CI supplies the
// tag-derived version; local builds use the same next-patch snapshot convention as :ui-builder-web.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

repositories {
  google()
  mavenCentral()
  // The IntelliJ Gradle plugin resolves its sandbox from project repositories, so it cannot inherit
  // the root's group-fenced port repository. This is repository metadata, not a port declaration:
  // the port remains declared only by :ui-builder, where the canvas uses it.
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
  // Material 3's desktop variant loads this at runtime (for example, SearchBar calls its
  // PredictiveBackHandler). IntelliJ's Compose foundation module does not include it. Keep its
  // transitive Compose runtime/UI dependencies out of the ZIP: those must stay supplied by the
  // platform, or the plugin would load a second Compose runtime and Skiko native library.
  implementation(
    "org.jetbrains.compose.ui:ui-backhandler-desktop:${libs.versions.compose.multiplatform.get()}"
  ) {
    exclude(group = "androidx.compose.runtime")
    exclude(group = "org.jetbrains.compose.runtime")
    exclude(group = "org.jetbrains.compose.ui")
  }
  add("integrationTestImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
  add("integrationTestImplementation", "org.kodein.di:kodein-di-jvm:7.26.1")
  // Starter calls TeamCityReporter even under NoCIServer, but its published Gradle metadata omits
  // the service-message classes that reporter loads at runtime.
  add("integrationTestRuntimeOnly", "org.jetbrains.teamcity:serviceMessages:2024.07")
  add("integrationTestImplementation", libs.kotlinx.coroutines.core)
  add(
    "integrationTestCompileOnly",
    "org.jetbrains.compose.runtime:runtime:${libs.versions.compose.multiplatform.get()}",
  )

  intellijPlatform {
    // IntelliJ uses one unified distribution from the 253 platform onward.
    intellijIdea("2026.2.3")
    bundledModule("intellij.platform.jewel.foundation")
    bundledModule("intellij.platform.jewel.ui")
    bundledModule("intellij.platform.jewel.ideLafBridge")
    bundledModule("intellij.libraries.compose.foundation.desktop")
    bundledModule("intellij.libraries.skiko")
    bundledPlugin("com.intellij.modules.json")
    testFramework(TestFrameworkType.Starter, configurationName = "integrationTestImplementation")
  }
}

intellijPlatform {
  pluginConfiguration {
    name = "Compose UI Builder POC"
    version = project.version.toString()
    ideaVersion {
      sinceBuild = "262"
      untilBuild = provider { null }
    }
  }
}

tasks.named<Zip>("buildPlugin") {
  archiveBaseName.set("compose-ui-builder-intellij-plugin")
  archiveVersion.set(project.version.toString())
}

intellijPlatformTesting.testIdeUi.register("installedPluginSmoke") {
  task {
    dependsOn(tasks.named("buildPlugin"))
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    useJUnitPlatform { excludeEngines("junit-vintage") }
    systemProperty(
      "path.to.build.plugin",
      tasks.named<Zip>("buildPlugin").flatMap { it.archiveFile }.get().asFile.absolutePath,
    )
    systemProperty(
      "ui.builder.smoke.project",
      layout.projectDirectory
        .dir("src/integrationTest/resources/smoke-project")
        .asFile
        .absolutePath,
    )
    systemProperty(
      "ui.builder.smoke.ide",
      providers.systemProperty("ui.builder.smoke.ide").getOrElse("all"),
    )
  }
}

// `check` must validate the installed archive's descriptor, not only compile against bundled IDE
// modules. That catches a misspelled or unavailable runtime module before a release ZIP is
// uploaded.
tasks.named("check") { dependsOn("verifyPluginStructure") }
