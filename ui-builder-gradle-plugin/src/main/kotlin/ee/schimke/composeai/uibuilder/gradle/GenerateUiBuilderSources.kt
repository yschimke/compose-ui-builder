package ee.schimke.composeai.uibuilder.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.process.ExecOperations

/** Git-index eligibility must be checked even when sources and generated outputs are unchanged. */
@UntrackedTask(
  because = "Every invocation validates the live Git index and the complete UID import closure"
)
abstract class GenerateUiBuilderSources : DefaultTask() {
  @get:Input abstract val entries: ListProperty<String>
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val records: ConfigurableFileCollection
  @get:Classpath abstract val generatorClasspath: ConfigurableFileCollection
  @get:Internal abstract val projectDirectory: DirectoryProperty
  // An output annotation makes Gradle mkdir before execution, defeating the CLI's refusal of
  // unowned directories. This task is untracked; source wiring uses an explicit builtBy
  // dependency, while the generator alone creates and owns the directory.
  @get:Internal abstract val outputDirectory: DirectoryProperty
  @get:Nested abstract val javaLauncher: Property<JavaLauncher>
  @get:Inject protected abstract val execOperations: ExecOperations

  @TaskAction
  fun generate() {
    require(entries.get().isNotEmpty()) { "[NO_ENTRY_FILES] Register at least one UID entry" }
    require(!records.isEmpty) { "Register pinned component records in digest order" }
    execOperations
      .javaexec {
        it.executable = javaLauncher.get().executablePath.asFile.absolutePath
        it.classpath = generatorClasspath
        it.mainClass.set("ee.schimke.composeai.uibuilder.codegen.ProductionGenerationCli")
        it.maxHeapSize = "512m"
        it.args(
          listOf(
            "generate",
            projectDirectory.get().asFile.absolutePath,
            outputDirectory.get().asFile.absolutePath,
            "--entries",
          ) + entries.get() + listOf("--records") + records.files.map { file -> file.absolutePath }
        )
      }
      .assertNormalExitValue()
  }
}
