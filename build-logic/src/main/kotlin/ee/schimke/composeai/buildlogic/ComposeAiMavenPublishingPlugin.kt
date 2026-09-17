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
 * Applied by a module that publishes a library. Applying it IS what puts the module in the release
 * set — `settings.gradle.kts` derives that set by looking for this plugin id, the BOM writes a
 * constraint per member, and the root build's `checkPublishedPomCoordinates` runs against each one.
 *
 * Nothing lists the published modules anywhere. compose-preview-server learned twice what a list
 * costs: two hand-kept copies of the set disagreed for six releases and shipped POMs naming an
 * artifact nobody had uploaded. Its root build carries that story in full.
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
    project.version = project.publishedVersion()

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
