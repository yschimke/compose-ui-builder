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

kotlin { jvmToolchain(17) }

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

  testImplementation(kotlin("test"))
}

// The catalog and generic render bundle belong to the runtime that controls their revision and
// persistence lifecycle. The frontend is only a build-time producer: no project dependency enters
// this module's runtime classpath or published POM.
tasks.processResources {
  from(
    rootProject.file("docs/design/fixtures/ui-builder/jetcaster-discover-capabilities-v1.json")
  ) {
    into("ee/schimke/composeai/uibuilder/catalogs")
    rename { "m3-catalog-v1.json" }
  }
  dependsOn(project(":ui-builder").tasks.named("composePreviewBundle"))
  from(project(":ui-builder").layout.buildDirectory.file("compose-previews/bundle.png")) {
    into("ee/schimke/composeai/uibuilder/renderer")
    rename { "ui-builder-renderer.bundle.png" }
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
    val offenders =
      resolvedComponents.get().filter { component ->
        component.startsWith("project ") ||
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
