package ee.schimke.composeai.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Puts this build's task types on the build-script classpath; configures nothing. The root applies
 * it so every subproject script inherits the classes, as `buildSrc` did without invalidating every
 * script on change.
 */
class BuildTasksPlugin : Plugin<Project> {
  override fun apply(target: Project) = Unit
}
