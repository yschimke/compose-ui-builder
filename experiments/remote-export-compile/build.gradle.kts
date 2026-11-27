// Compiles the Remote Kotlin the export tests write against the released AndroidX libraries a
// consumer builds it with, so an API the emitter assumes (a constructor, an overload, an import)
// is checked by kotlinc rather than by reading sources. See README.md.
plugins {
  id("com.android.library") version "9.5.3"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

// `-Pcomposeai.remoteCompose=snapshot` (see settings.gradle.kts) resolves all three groups from one
// androidx.dev build, as `1.0.0-SNAPSHOT`; the release defaults are the versions remote-m3-catalog,
// the builder's Remote consumer, pins. The exporter writes alpha24 spellings, which only the
// snapshot lane carries until 1.0.0-alpha24 is released.
// TODO(alpha24): move these defaults to 1.0.0-alpha24 (and its wear-compose-remote / glance-wear
// pair) when it ships, and compile CI on the release lane again.
val snapshotLane =
  providers.gradleProperty("composeai.remoteCompose").orNull?.trim()?.lowercase() == "snapshot"
val composeRemote =
  if (snapshotLane) providers.provider { "1.0.0-SNAPSHOT" }
  else providers.gradleProperty("composeRemote").orElse("1.0.0-alpha21")
val wearComposeRemote =
  if (snapshotLane) providers.provider { "1.0.0-SNAPSHOT" }
  else providers.gradleProperty("wearComposeRemote").orElse("1.0.0-alpha13")
val glanceWear =
  if (snapshotLane) providers.provider { "1.0.0-SNAPSHOT" }
  else providers.gradleProperty("glanceWear").orElse("1.0.0-alpha20")

android {
  namespace = "proof.export"
  compileSdk = providers.gradleProperty("compileSdk").orElse("36").get().toInt()
  defaultConfig { minSdk = 26 }
  buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation("androidx.compose.remote:remote-creation-compose:${composeRemote.get()}")
  // After alpha23 the state types (`RemoteFloat`, `MutableRemoteInt`, `rf`, `lerp`, …) moved into
  // their own artifact. remote-creation-compose depends on it, but the export imports from it
  // directly, so it is named.
  if (snapshotLane) {
    implementation("androidx.compose.remote:remote-creation-expression:${composeRemote.get()}")
  }
  // A launcher widget's preview is `RemoteContentPreview`.
  implementation("androidx.compose.remote:remote-tooling-preview:${composeRemote.get()}")
  implementation("androidx.wear.compose.remote:remote-material3:${wearComposeRemote.get()}")
  implementation("androidx.glance.wear:wear:${glanceWear.get()}")
  implementation("androidx.glance.wear:wear-core:${glanceWear.get()}")
  implementation("androidx.glance.wear:wear-tooling-preview:${glanceWear.get()}")
  implementation("androidx.compose.ui:ui-tooling-preview:1.13.0")
  implementation("androidx.compose.material:material-icons-core:1.7.8")
}

// Every proof file the export tests write, each moved into a package of its own: several are the
// same widget exported with one property changed, so they share class names.
abstract class StageProofSources : DefaultTask() {
  @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val proofs: ConfigurableFileCollection

  @get:OutputDirectory abstract val output: DirectoryProperty

  @TaskAction
  fun stage() {
    val root = output.get().asFile
    root.deleteRecursively()
    var count = 0
    proofs.files.filter { it.isDirectory }.forEach { directory ->
      directory.listFiles { file -> file.extension == "kt" }.orEmpty().sorted().forEach { file ->
        val pkg = "proof.${directory.name.replace("-", "")}.${file.nameWithoutExtension.lowercase()}"
        val target = root.resolve(pkg.replace('.', '/')).resolve(file.name)
        target.parentFile.mkdirs()
        val lines = file.readLines()
        // A components export carries no package: give it one after its file annotations.
        val packaged =
          if (lines.any { it.startsWith("package ") })
            lines.map { if (it.startsWith("package ")) "package $pkg" else it }
          else {
            val at = lines.indexOfLast { it.startsWith("@file:") } + 1
            lines.take(at) + listOf("", "package $pkg") + lines.drop(at)
          }
        target.writeText(packaged.joinToString("\n"))
        count++
      }
    }
    check(count > 0) {
      "No proof sources under ui-builder-export/build: run :ui-builder-export:jvmTest first"
    }
  }
}

val stageProofSources by
  tasks.registering(StageProofSources::class) {
    val export = rootDir.resolve("../../ui-builder-export/build")
    proofs.from(listOf("draw-proof", "remote-call-proof", "remote-state-selection").map(export::resolve))
    output.set(layout.buildDirectory.dir("proof-sources"))
  }

androidComponents {
  onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(stageProofSources, StageProofSources::output)
  }
}
