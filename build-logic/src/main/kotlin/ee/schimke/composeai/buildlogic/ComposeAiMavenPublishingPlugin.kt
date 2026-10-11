package ee.schimke.composeai.buildlogic

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

abstract class ComposeAiMavenPublishingExtension @Inject constructor(objects: ObjectFactory) {
  /**
   * Defaulted from the project path by [publishedArtifactId]; set it only for a module whose
   * coordinate cannot follow the rule, and say why at the call site.
   */
  val artifactId: Property<String> = objects.property(String::class.java)

  val displayName: Property<String> = objects.property(String::class.java)

  val description: Property<String> = objects.property(String::class.java)

  val inceptionYear: Property<String> = objects.property(String::class.java).convention("2026")

  fun coordinates(displayName: String, description: String) {
    this.displayName.set(displayName)
    this.description.set(description)
  }
}

/**
 * Applied by a module that publishes a library. Applying it is what puts the module in the release
 * set: `settings.gradle.kts` derives the set from this plugin id, the BOM constrains each member,
 * and `checkPublishedPomCoordinates` checks each. Nothing lists the published modules anywhere.
 */
class ComposeAiMavenPublishingPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("com.vanniktech.maven.publish")

    val extension =
      project.extensions.create(
        "composeAiMavenPublishing",
        ComposeAiMavenPublishingExtension::class.java,
      )

    project.group = "ee.schimke.composeai"
    project.version = project.publishedVersion(project.publishedArtifactId())

    project.afterEvaluate {
      project.configureComposeAiPublication(
        artifactId = extension.artifactId.orNull ?: project.publishedArtifactId(),
        displayName =
          extension.displayName.orNull
            ?: error("composeAiMavenPublishing.coordinates(...) is required in ${project.path}"),
        artifactDescription =
          extension.description.orNull
            ?: error("composeAiMavenPublishing.coordinates(...) is required in ${project.path}"),
        inceptionYear = extension.inceptionYear,
      )
    }
  }
}
