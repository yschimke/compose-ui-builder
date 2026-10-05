plugins {
  `java-gradle-plugin`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  id("composeai.maven-publishing")
}

base { archivesName.set("compose-preview-" + project.name) }

ktfmt { googleStyle() }

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

gradlePlugin {
  plugins {
    create("uiBuilder") {
      id = "ee.schimke.compose-ui-builder"
      implementationClass = "ee.schimke.composeai.uibuilder.gradle.UiBuilderPlugin"
      displayName = "Compose UI Builder generation"
      description = "Generate Compose sources from explicitly registered, tracked UID contracts."
    }
  }
}

val kotlinPluginForTests = configurations.create("kotlinPluginForTests")
val generatorForTests = configurations.create("generatorForTests")

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  kotlinPluginForTests("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
  testImplementation(gradleTestKit())
  testImplementation(kotlin("test"))
  generatorForTests(project(":ui-builder-codegen-jvm"))
}

// Selective releases can retain an older generator. Bake its resolved publication version,
// not this plugin's version. The project reference also records the release-planner edge.
evaluationDependsOn(":ui-builder-codegen-jvm")

val generatorVersion = project(":ui-builder-codegen-jvm").version.toString()
val writeGeneratorVersion =
  tasks.register("writeGeneratorVersion") {
    val versionValue = generatorVersion
    val output =
      layout.buildDirectory.file("generated/pluginResources/ui-builder-generator.properties")
    inputs.property("generatorVersion", generatorVersion)
    outputs.file(output)
    doLast {
      output.get().asFile.apply {
        parentFile.mkdirs()
        writeText("generatorVersion=$versionValue\n")
      }
    }
  }

sourceSets.main {
  resources.srcDir(writeGeneratorVersion.map { it.outputs.files.singleFile.parentFile })
}

// TestKit injects the plugin in a parent classloader; make the compile-only Kotlin API
// visible there too. The external Maven consumer verifies normal plugin resolution.
tasks.pluginUnderTestMetadata { pluginClasspath.from(kotlinPluginForTests) }

tasks.test {
  inputs.files(generatorForTests)
  systemProperty("generatorClasspath", generatorForTests.asPath)
}

composeAiMavenPublishing {
  coordinates(
    displayName = "Compose UI Builder — Gradle plugin",
    description = "Opt-in Kotlin source-set integration for durable UID build generation.",
  )
}
