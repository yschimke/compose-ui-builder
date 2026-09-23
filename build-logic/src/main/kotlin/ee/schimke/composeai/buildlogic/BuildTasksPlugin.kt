package ee.schimke.composeai.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Puts this build's task types on the build-script classpath.
 *
 * It configures nothing. Classes of an included plugin build reach a script only through a plugin
 * that script applies, so the root project applies this one and every subproject script inherits
 * the root's classloader — which is what `buildSrc` used to provide, at the price of invalidating
 * every script whenever any of it changed.
 */
class BuildTasksPlugin : Plugin<Project> {
  override fun apply(target: Project) = Unit
}
