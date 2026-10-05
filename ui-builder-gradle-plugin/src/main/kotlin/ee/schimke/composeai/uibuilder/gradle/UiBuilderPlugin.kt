package ee.schimke.composeai.uibuilder.gradle

import java.util.Properties
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

abstract class UiBuilderSourceSet @Inject constructor(private val sourceSetName: String) : Named {
  override fun getName(): String = sourceSetName

  abstract val entries: ListProperty<String>
  abstract val records: ConfigurableFileCollection
}

abstract class UiBuilderGenerationExtension @Inject constructor(objects: ObjectFactory) {
  abstract val generatorVersion: Property<String>
  val sourceSets: NamedDomainObjectContainer<UiBuilderSourceSet> =
    objects.domainObjectContainer(UiBuilderSourceSet::class.java) { name ->
      require(name.matches(Regex("[a-z][A-Za-z0-9]*"))) {
        "UID source set names must start with a lowercase letter and contain only letters and digits"
      }
      objects.newInstance(UiBuilderSourceSet::class.java, name)
    }

  fun sourceSets(action: Action<NamedDomainObjectContainer<UiBuilderSourceSet>>) {
    action.execute(sourceSets)
  }
}

class UiBuilderPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension =
      project.extensions.create("uiBuilderGeneration", UiBuilderGenerationExtension::class.java)
    val defaults =
      Properties().apply {
        UiBuilderPlugin::class
          .java
          .getResourceAsStream("/ui-builder-generator.properties")!!
          .use(::load)
      }
    extension.generatorVersion.convention(defaults.getProperty("generatorVersion"))
    val generator =
      project.configurations.create("uiBuilderGenerator") {
        it.isCanBeConsumed = false
        it.isCanBeResolved = true
        it.description = "Isolated JVM classpath for UID source generation"
      }
    generator.defaultDependencies { dependencies ->
      dependencies.add(
        project.dependencies.create(
          "ee.schimke.composeai:compose-preview-ui-builder-codegen-jvm:${extension.generatorVersion.get()}"
        )
      )
    }
    project.pluginManager.apply("jvm-toolchains")
    val toolchains = project.extensions.getByType(JavaToolchainService::class.java)
    val aggregate =
      project.tasks.register("generateUiBuilderSources") {
        it.group = "code generation"
        it.description = "Generate all explicitly registered UID source sets."
      }
    extension.sourceSets.all { registration ->
      val task =
        project.tasks.register(
          "generateUiBuilder${registration.name.replaceFirstChar(Char::uppercaseChar)}",
          GenerateUiBuilderSources::class.java,
        ) {
          it.group = "code generation"
          it.description = "Generate tracked UID contracts for ${registration.name}."
          it.entries.set(registration.entries)
          it.records.from(registration.records)
          it.generatorClasspath.from(generator)
          it.projectDirectory.set(project.layout.projectDirectory)
          it.outputDirectory.set(
            project.layout.projectDirectory.dir(
              "build/generated/uiBuilder/${registration.name}/kotlin"
            )
          )
          it.javaLauncher.set(
            toolchains.launcherFor { spec -> spec.languageVersion.set(JavaLanguageVersion.of(17)) }
          )
        }
      aggregate.configure { it.dependsOn(task) }
      listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform").forEach { pluginId ->
        project.pluginManager.withPlugin(pluginId) {
          project.extensions
            .getByType(KotlinProjectExtension::class.java)
            .sourceSets
            .matching { it.name == registration.name }
            .configureEach {
              it.kotlin.srcDir(
                project
                  .files(task.flatMap { generation -> generation.outputDirectory })
                  .builtBy(task)
              )
            }
        }
      }
    }
    project.afterEvaluate {
      if (extension.sourceSets.isNotEmpty()) {
        if (
          !project.pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") &&
            !project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform")
        ) {
          throw GradleException("UID generation requires the Kotlin JVM or Multiplatform plugin")
        }
        val kotlin = project.extensions.getByType(KotlinProjectExtension::class.java)
        extension.sourceSets.forEach {
          if (kotlin.sourceSets.findByName(it.name) == null) {
            throw GradleException("UID generation source set '${it.name}' does not exist in Kotlin")
          }
        }
      }
    }
  }
}
