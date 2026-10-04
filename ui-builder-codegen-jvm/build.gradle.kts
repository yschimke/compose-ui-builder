plugins {
  `java-library`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  id("composeai.maven-publishing")
}

base { archivesName.set("compose-preview-" + project.name) }

ktfmt { googleStyle() }

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

dependencies {
  api(project(":ui-builder-export"))
  implementation(libs.kotlin.compiler.embeddable)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    displayName = "Compose UI Builder — Build generator",
    description = "Opt-in stateless Compose source generation from project-owned UID contracts.",
  )
}

tasks.test {
  systemProperty("consumerProject", rootProject.file("ui-builder-production-consumer").absolutePath)
  systemProperty("fixtureRecords", rootProject.file("docs/design/fixtures/ui-builder").absolutePath)
}
