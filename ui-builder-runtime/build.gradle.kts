import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.PathSensitivity

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  id("composeai.maven-publishing")
}

kotlin {
  jvmToolchain(libs.versions.java.server.get().toInt())

  // The service seam the server consumes: explicit API, with the committed
  // `api/ui-builder-runtime.api` dump recording exactly what the host may rely on.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin.
tasks.named("check") { dependsOn("checkKotlinAbi") }

ktfmt { googleStyle() }

base { archivesName.set("compose-preview-" + project.name) }

dependencies {
  // `ui-builder-protocol`'s version comes from this platform. A BOM adds no artifact, so
  // `checkUiBuilderRuntimeBoundary` does not need to allow it.
  api(platform(libs.composeai.contracts.bom))
  // The public service port deliberately speaks the released v1 contract types. Keeping this `api`
  // makes those types available to the host implementing or decorating the port.
  api(libs.composeai.ui.builder.protocol)
  // Record types are part of this module's public surface; the tools platform supplies
  // `screen-model`'s version so the published POM is resolvable.
  api(platform(libs.composeai.tools.bom))
  api(libs.composeai.screen.model)
  implementation(libs.kotlinx.serialization.json)
  // Shared binding semantics, so the browser and service accept the same state reads. `api` because
  // the executor's Builder names `CatalogOwnership`.
  api(project(":ui-builder-export"))

  // The packaged render bundle `PackagedUiBuilderRenderBundle.copyTo` materializes, as a sibling
  // artifact (see `:ui-builder-render-bundle`'s build file). `api` because `copyTo` reads it off
  // the consumer's runtime classpath.
  api(project(":ui-builder-render-bundle"))

  testImplementation(kotlin("test"))
}

// The catalog belongs to the runtime that controls its revision and persistence lifecycle.
// An application's exported typed catalog pair, smoke-tested only when its directory is named.
val externalCatalog =
  providers.gradleProperty("typedCatalogSmokeDirectory").orNull?.let { rootProject.file(it) }

tasks.named<Test>("test") {
  if (externalCatalog != null) {
    inputs.dir(externalCatalog)
    systemProperty("typedCatalogSmokeDirectory", externalCatalog.absolutePath)
  } else {
    exclude("**/ExternalTypedCatalogSmokeTest*")
  }
}

tasks.processResources {
  from(rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json")) {
    into("ee/schimke/composeai/uibuilder/catalogs")
    rename { "m3-catalog-v1.json" }
  }
  // The readiness gate's shape table (written by `DecoderShapeFixtureTest`), shipped so hosts
  // running the gate can check their copy against the pinned release.
  from(rootProject.file(".github/scripts/decoder-shapes.json")) {
    into("ee/schimke/composeai/uibuilder/service")
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  providers.gradleProperty("uiBuilderCollaborationSoakMinutes").orNull?.let {
    systemProperty("uiBuilderCollaborationSoakMinutes", it)
  }
  // `SlotAcceptanceTest` rewrites its committed table when asked; see the class for the command.
  providers.gradleProperty("uiBuilderSlotAcceptanceUpdate").orNull?.let {
    systemProperty("uiBuilderSlotAcceptanceUpdate", it)
  }
  // The committed goldens the tests read by path, declared as inputs so a golden-only change
  // re-runs the test instead of hitting the up-to-date or build cache.
  inputs
    .files(
      rootProject.fileTree("docs/design/fixtures/ui-builder") {
        include("*-capabilities-v1.json")
        include("slot-acceptance-v1.json")
      }
    )
    .withPropertyName("uiBuilderGoldenFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  // Likewise for the shape table `DecoderShapeFixtureTest` reads and rewrites; the update switch is
  // an input too.
  inputs
    .file(rootProject.file(".github/scripts/decoder-shapes.json"))
    .withPropertyName("decoderShapes")
    .withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.property(
    "updateDecoderShapes",
    providers.environmentVariable("UPDATE_DECODER_SHAPES").orElse(""),
  )
}

abstract class CheckUiBuilderRuntimeBoundary : DefaultTask() {
  @get:Input abstract val resolvedComponents: SetProperty<String>

  @TaskAction
  fun checkBoundary() {
    val allowedComposeAi =
      setOf(
        "module ee.schimke.composeai:ui-builder-protocol",
        "module ee.schimke.composeai:ui-builder-protocol-jvm",
        "module ee.schimke.composeai:screen-document",
        "module ee.schimke.composeai:screen-document-jvm",
        "module ee.schimke.composeai:screen-model",
        "module ee.schimke.composeai:screen-model-jvm",
        // Component record types now live in the contracts' wire module (data shapes only).
        "module ee.schimke.composeai:component-catalog-protocol",
        "module ee.schimke.composeai:component-catalog-protocol-jvm",
      )
    /**
     * The packaged render data and shared document semantics on this classpath.
     *
     * `:ui-builder-render-bundle` has no code: its embedded classes are opaque bytes a daemon
     * unpacks later. `:ui-builder-export` holds Compose-free document rules shared with the
     * browser. Naming exact coordinates keeps the exclusions on the transitive graph.
     */
    val allowedProjects = setOf("project :ui-builder-render-bundle", "project :ui-builder-export")
    val offenders =
      resolvedComponents.get().filter { component ->
        (component.startsWith("project ") && component !in allowedProjects) ||
          (component.startsWith("module ee.schimke.composeai:") &&
            component !in allowedComposeAi) ||
          component.startsWith("module io.ktor:") ||
          component.startsWith("module org.jetbrains.compose") ||
          component.startsWith("module androidx.compose") ||
          component.startsWith("module io.modelcontextprotocol:")
      }
    check(offenders.isEmpty()) {
      "UI-builder runtime must stay transport-, renderer-, daemon-, MCP-, and Compose-UI-free. " +
        "Found: ${offenders.sorted().joinToString(", ")}"
    }
  }
}

tasks.register<CheckUiBuilderRuntimeBoundary>("checkUiBuilderRuntimeBoundary") {
  description = "Checks the published UI-builder runtime's resolved dependency boundary."
  group = "verification"
  resolvedComponents.set(
    configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts
          .map { artifact ->
            when (val id = artifact.id.componentIdentifier) {
              is ProjectComponentIdentifier -> "project ${id.projectPath}"
              is ModuleComponentIdentifier -> "module ${id.group}:${id.module}"
              else -> "other ${id.displayName}"
            }
          }
          .toSet()
      }
    }
  )
}

tasks.named("check") { dependsOn("checkUiBuilderRuntimeBoundary") }

composeAiMavenPublishing {
  coordinates(
    displayName = "Compose UI Builder — Runtime",
    description =
      "Persistent collaborative UI-builder service, catalog validation, and revision-pinned " +
        "export orchestration.",
  )
}
