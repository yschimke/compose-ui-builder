plugins { `kotlin-dsl` }

// `java-server`, the floor everything a consumer resolves compiles at. Convention plugins run in
// the Gradle JVM, which this build already requires to be that floor.
kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

dependencies {
  implementation("com.vanniktech:gradle-maven-publish-plugin:${libs.versions.maven.publish.get()}")
  testImplementation(kotlin("test-junit5"))
}

tasks.test { useJUnitPlatform() }

gradlePlugin {
  plugins {
    register("composeAiMavenPublishing") {
      id = "composeai.maven-publishing"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiMavenPublishingPlugin"
    }
    register("composeAiPlatformPublishing") {
      id = "composeai.maven-publishing-platform"
      implementationClass = "ee.schimke.composeai.buildlogic.ComposeAiPlatformPublishingPlugin"
    }
  }
}
