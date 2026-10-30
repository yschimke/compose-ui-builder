// Compiles the Remote Kotlin the export tests write against the released AndroidX libraries a
// consumer builds it with, so an API the emitter assumes (a constructor, an overload, an import)
// is checked by kotlinc rather than by reading sources. See README.md.
plugins {
  id("com.android.library") version "9.5.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

// The versions remote-m3-catalog, the builder's Remote consumer, pins.
val composeRemote = providers.gradleProperty("composeRemote").orElse("1.0.0-alpha21")
val wearComposeRemote = providers.gradleProperty("wearComposeRemote").orElse("1.0.0-alpha13")
val glanceWear = providers.gradleProperty("glanceWear").orElse("1.0.0-alpha20")

android {
  namespace = "proof.export"
  compileSdk = providers.gradleProperty("compileSdk").orElse("36").get().toInt()
  defaultConfig { minSdk = 26 }
  buildFeatures { compose = true }
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation("androidx.compose.remote:remote-creation-compose:${composeRemote.get()}")
  implementation("androidx.wear.compose.remote:remote-material3:${wearComposeRemote.get()}")
  implementation("androidx.glance.wear:wear:${glanceWear.get()}")
  implementation("androidx.glance.wear:wear-core:${glanceWear.get()}")
  implementation("androidx.glance.wear:wear-tooling-preview:${glanceWear.get()}")
  implementation("androidx.compose.ui:ui-tooling-preview:1.12.1")
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
        target.writeText(
          file.readLines().joinToString("\n") { if (it.startsWith("package ")) "package $pkg" else it }
        )
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
    proofs.from(listOf("draw-proof", "remote-call-proof").map(export::resolve))
    output.set(layout.buildDirectory.dir("proof-sources"))
  }

androidComponents {
  onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(stageProofSources, StageProofSources::output)
  }
}
