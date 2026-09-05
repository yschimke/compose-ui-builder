import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"

val publishedArtifactId = "compose-preview-ui-builder-runtime"

kotlin {
  jvmToolchain(17)

  // Published as `ee.schimke.composeai:compose-preview-ui-builder-runtime` and consumed across a
  // repository boundary, so every declaration states its visibility and every public one its
  // return type, and the committed `api/ui-builder-runtime.api` dump records, in a form a reviewer
  // reads as a diff, exactly what a consumer may rely on.
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

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val current =
        Regex(""""\.":\s*"([^"]+)"""")
          .find(rootDir.resolve(".release-please-manifest.json").readText())!!
          .groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set(publishedArtifactId) }

dependencies {
  // The public service port deliberately speaks the released v1 contract types. Keeping this `api`
  // makes the generated POM usable by a host implementing or decorating the port.
  api(libs.composeai.ui.builder.protocol)
  implementation(libs.kotlinx.serialization.json)

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
  // frontend build output, and a published server-side artifact carrying `:ui-builder`'s compiled
  // JVM previews was an edge across the layer line that a repository split cannot follow, and a
  // silent pin of the frontend's JVM target to this module's. See
  // yschimke/compose-preview-server#346
  // and `:ui-builder-render-bundle`'s own build file.
  api(project(":ui-builder-render-bundle"))

  testImplementation(kotlin("test"))
}

// The catalog belongs to the runtime that controls its revision and persistence lifecycle. The
// render bundle used to be copied in beside it, from `:ui-builder`'s build directory; it is now
// `:ui-builder-render-bundle`'s published artifact, for the reasons that dependency records.
tasks.processResources {
  from(rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json")) {
    into("ee/schimke/composeai/uibuilder/catalogs")
    rename { "m3-catalog-v1.json" }
  }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

abstract class CheckUiBuilderRuntimeBoundary : DefaultTask() {
  @get:Input abstract val resolvedComponents: SetProperty<String>

  @TaskAction
  fun checkBoundary() {
    val allowedComposeAi =
      setOf(
        "module ee.schimke.composeai:ui-builder-protocol",
        "module ee.schimke.composeai:ui-builder-protocol-jvm",
      )
    /**
     * The one project on this classpath, and the reason it does not weaken the gate.
     *
     * Everything this check exists to keep out — transports, renderers, daemons, MCP, Compose UI —
     * is *code*, reachable because it sits on the classpath. `:ui-builder-render-bundle` has no
     * source set at all: its jar is one PNG. The polyglot's embedded ZIP does carry Compose and
     * `:ui-builder` classes, but they are opaque bytes a daemon unpacks later, not entries a
     * classloader here can see — which is exactly the property this module already relied on when
     * the same bytes were its own resource.
     *
     * Named as a single constant rather than folded into [allowedComposeAi] because it is a
     * *project*, and a project is the thing this check is otherwise absolute about.
     */
    val allowedProject = "project :ui-builder-render-bundle"
    val offenders =
      resolvedComponents.get().filter { component ->
        (component.startsWith("project ") && component != allowedProject) ||
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

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — UI Builder Runtime")
    description.set(
      "Persistent collaborative UI-builder service, catalog validation, and revision-pinned export orchestration."
    )
    url.set("https://github.com/yschimke/compose-preview-server")
    inceptionYear.set("2026")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("yschimke")
        name.set("Yuri Schimke")
        url.set("https://github.com/yschimke")
      }
    }
    scm {
      url.set("https://github.com/yschimke/compose-preview-server")
      connection.set("scm:git:https://github.com/yschimke/compose-preview-server.git")
      developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-preview-server.git")
    }
  }
}
