plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.preview)
}

ktfmt { googleStyle() }

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

val uiBuilderGenerator = configurations.create("uiBuilderGenerator")

dependencies {
  val publishedGeneratorVersion = providers.gradleProperty("uiBuilderGeneratorVersion").orNull
  if (publishedGeneratorVersion == null) {
    uiBuilderGenerator(project(":ui-builder-codegen-jvm"))
  } else {
    uiBuilderGenerator(
      "ee.schimke.composeai:compose-preview-ui-builder-codegen-jvm:$publishedGeneratorVersion"
    )
  }
  implementation(compose.material3)
  implementation(libs.material.icons.extended)
  implementation(compose.foundation)
  implementation(compose.ui)
  implementation(compose.desktop.currentOs)
  implementation(libs.compose.ui.tooling.preview)
  testImplementation(kotlin("test"))
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class) testImplementation(compose.uiTest)
}

val entries = listOf("src/main/ui/Library.uid", "src/main/ui/Queue.uid")
val records =
  files(
    rootProject.file("docs/design/fixtures/ui-builder/compose-foundation-components-v1.json"),
    rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"),
  )
// Git-index eligibility is checked on EVERY build, even when Kotlin compilation is up to date.
val verifyUiBuilderInputs =
  tasks.register<JavaExec>("verifyUiBuilderInputs") {
    classpath = uiBuilderGenerator
    mainClass.set("ee.schimke.composeai.uibuilder.codegen.ProductionGenerationCli")
    args(listOf("validate", projectDir.absolutePath) + entries)
  }
val generateUiBuilderSources =
  tasks.register<JavaExec>("generateUiBuilderSources") {
    dependsOn(verifyUiBuilderInputs)
    classpath = uiBuilderGenerator
    mainClass.set("ee.schimke.composeai.uibuilder.codegen.ProductionGenerationCli")
    // Deliberately always regenerate; no stale source or Git-index result can be cached.
    args(
      listOf(
        "generate",
        projectDir.absolutePath,
        layout.buildDirectory.dir("generated/uiBuilder").get().asFile.absolutePath,
        "--entries",
      ) + entries + listOf("--records") + records.files.map { it.absolutePath }
    )
  }

kotlin.sourceSets.named("main") { kotlin.srcDir(layout.buildDirectory.dir("generated/uiBuilder")) }

tasks.named("compileKotlin") { dependsOn(generateUiBuilderSources) }
