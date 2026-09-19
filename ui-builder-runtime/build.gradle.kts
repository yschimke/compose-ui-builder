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

  // This is the service seam the server consumes, so every declaration states its visibility and
  // every public one its return type. The committed `api/ui-builder-runtime.api` dump records, in a
  // form a reviewer reads as a diff, exactly what the host may rely on.
  //
  // The gate fits HERE and deliberately not on `:server`: this module is five files whose surface
  // is already a designed service port (`UiBuilderServicePort` and its request/response algebra),
  // while `:server` would have to mark ~1,200 declarations `public` and freeze an ABI nobody
  // designed — the reason its build file gives for staying off the gate, and it still holds.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin.
tasks.named("check") { dependsOn("checkKotlinAbi") }

ktfmt { googleStyle() }

base { archivesName.set("compose-preview-" + project.name) }

dependencies {
  // `ui-builder-protocol` carries no version of its own; this platform supplies it (see the
  // catalog). A BOM is metadata, not an artifact, so it adds nothing to `runtimeClasspath`'s
  // resolved artifacts and `checkUiBuilderRuntimeBoundary` below neither sees it nor needs to
  // allow it.
  api(platform(libs.composeai.contracts.bom))
  // The public service port deliberately speaks the released v1 contract types. Keeping this `api`
  // makes those types available to the host implementing or decorating the port.
  api(libs.composeai.ui.builder.protocol)
  implementation(libs.kotlinx.serialization.json)
  // Shared binding semantics: the browser and persistent service must accept the same state reads.
  implementation(project(":ui-builder-export"))

  // The packaged render bundle `PackagedUiBuilderRenderBundle.copyTo` materializes, as a sibling
  // artifact rather than bytes in this jar.
  //
  // `api`, not `implementation`: `copyTo` is public API of this module and reads the bundle off the
  // *consumer's* classpath, so a consumer that resolves this module and calls it has to receive the
  // bundle too. An `implementation` edge would keep it off their compile classpath, which `copyTo`
  // does not need, and off their runtime classpath, which it does — the failure landing as
  // "packaged UI-builder renderer bundle is missing" at the first render rather than at resolve.
  //
  // Why this is a dependency at all, when it used to be a `processResources` copy: the bundle is a
  // frontend build output, and a server-side jar carrying `:ui-builder`'s compiled
  // JVM previews was an edge across the layer line that a repository split cannot follow, and a
  // silent pin of the frontend's JVM target to this module's. See
  // yschimke/compose-preview-server#346
  // and `:ui-builder-render-bundle`'s own build file.
  api(project(":ui-builder-render-bundle"))

  testImplementation(kotlin("test"))
}

// The catalog belongs to the runtime that controls its revision and persistence lifecycle. The
// render bundle used to be copied in beside it, from `:ui-builder`'s build directory; it is now
// `:ui-builder-render-bundle`'s packaged artifact, for the reasons that dependency records.
tasks.processResources {
  from(rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json")) {
    into("ee/schimke/composeai/uibuilder/catalogs")
    rename { "m3-catalog-v1.json" }
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
  // `SynthesisedCatalogGoldenTest` rewrites the frozen `wear-m3` / `remote-m3` catalogs when asked;
  // see the class for what a failure means and why reading the diff is the point. A Gradle property
  // rather than a bare `-D` for the same reason as the line above: `-D` on the command line reaches
  // the Gradle JVM, not the forked test JVM, and the silent no-op that follows is a confusing half
  // hour.
  providers.gradleProperty("uiBuilderGoldens").orNull?.let {
    systemProperty("ui.builder.goldens", it)
  }
  // The committed goldens those two tests READ, declared so their contents are part of this task's
  // key. They live at the repository root and are opened by path at execution time, so without this
  // Gradle has no idea they exist: a commit that changes only a golden leaves the task up to date —
  // or restores it from the build cache, which CI has enabled — and the assertion never runs. A
  // stale or corrupted golden would then pass `check`, which is the one thing a golden cannot be
  // allowed to do.
  inputs
    .files(
      rootProject.fileTree("docs/design/fixtures/ui-builder") {
        include("*-capabilities-v1.json")
        include("slot-acceptance-v1.json")
      }
    )
    .withPropertyName("uiBuilderGoldenFixtures")
    .withPathSensitivity(PathSensitivity.RELATIVE)
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
      )
    /**
     * The packaged render data and shared document semantics on this classpath.
     *
     * Everything this check exists to keep out — transports, renderers, daemons, MCP, Compose UI —
     * is *code*, reachable because it sits on the classpath. `:ui-builder-render-bundle` has no
     * source set at all: its jar is one PNG. The polyglot's embedded ZIP does carry Compose and
     * `:ui-builder` classes, but they are opaque bytes a daemon unpacks later, not entries a
     * classloader here can see — which is exactly the property this module already relied on when
     * the same bytes were its own resource.
     *
     * `:ui-builder-export` holds the common, Compose-free document and property rules that the
     * browser uses too. It depends on the transport-free screen model, not a renderer. Naming those
     * exact coordinates keeps the same exclusions on the resolved transitive graph.
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
