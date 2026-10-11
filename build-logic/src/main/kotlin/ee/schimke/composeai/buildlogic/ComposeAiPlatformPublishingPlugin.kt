package ee.schimke.composeai.buildlogic

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

abstract class ComposeAiPlatformPublishingExtension @Inject constructor(objects: ObjectFactory) {
  val artifactId: Property<String> = objects.property(String::class.java)

  val displayName: Property<String> = objects.property(String::class.java)

  val description: Property<String> = objects.property(String::class.java)

  val inceptionYear: Property<String> = objects.property(String::class.java).convention("2026")

  fun coordinates(artifactId: String, displayName: String, description: String) {
    this.artifactId.set(artifactId)
    this.displayName.set(displayName)
    this.description.set(description)
  }
}

/**
 * The BOM's own plugin, separate from [ComposeAiMavenPublishingPlugin] because a platform has no
 * sources or ABI and must not be in the derived library release set. Its artifact id is explicit:
 * `:bom` would derive to the over-broad `compose-preview-bom`.
 */
class ComposeAiPlatformPublishingPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("java-platform")
    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("com.vanniktech.maven.publish")

    val extension =
      project.extensions.create(
        "composeAiPlatformPublishing",
        ComposeAiPlatformPublishingExtension::class.java,
      )

    project.group = "ee.schimke.composeai"
    project.version = project.publishedVersion()

    project.afterEvaluate {
      project.configureComposeAiPublication(
        artifactId =
          extension.artifactId.orNull
            ?: error("composeAiPlatformPublishing.artifactId is required"),
        displayName =
          extension.displayName.orNull
            ?: error("composeAiPlatformPublishing.displayName is required"),
        artifactDescription =
          extension.description.orNull
            ?: error("composeAiPlatformPublishing.description is required"),
        inceptionYear = extension.inceptionYear,
      )
    }
  }
}
