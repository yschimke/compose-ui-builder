package ee.schimke.composeai.uibuilder.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class UiBuilderPluginTest {
  private val root = Files.createTempDirectory("uid-plugin-test-").toFile()

  @AfterTest
  fun cleanup() {
    root.deleteRecursively()
  }

  private fun write(path: String, text: String) {
    root.resolve(path).apply {
      parentFile.mkdirs()
      writeText(text)
    }
  }

  private fun git(vararg args: String) {
    val process =
      ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  private fun model(path: String, name: String) {
    write(
      path,
      """{"schema":"compose-ui-builder-production/v1","models":[{
      "id":"$name","kotlinType":"example.$name","ownership":"generated",
      "fields":[{"name":"title","type":{"kind":"scalar","scalar":"string"}}]}]}""",
    )
  }

  private fun fixture(
    multiplatform: Boolean = false,
    registrations: String =
      "create(\"main\") { entries.addAll(\"First.uid\", \"Second.uid\"); records.from(\"records.json\") }",
  ) {
    git("init", "-q")
    write(
      "settings.gradle.kts",
      """
      pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
      dependencyResolutionManagement { repositories { mavenCentral(); google() } }
      rootProject.name = "plugin-consumer"
      """
        .trimIndent(),
    )
    write(
      "gradle.properties",
      """
      org.gradle.configuration-cache=true
      org.gradle.configuration-cache.problems=fail
      org.gradle.caching=true
      org.gradle.jvmargs=-Xmx768m
      kotlin.daemon.jvmargs=-Xmx768m
      org.gradle.workers.max=2
      """
        .trimIndent(),
    )
    val generatorFiles =
      System.getProperty("generatorClasspath").split(File.pathSeparator).joinToString(",") {
        "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
      }
    write(
      "build.gradle.kts",
      """
      plugins {
        id("ee.schimke.compose-ui-builder")
        kotlin("${if (multiplatform) "multiplatform" else "jvm"}")
      }
      kotlin { ${if (multiplatform) "jvm(); wasmJs { nodejs() };" else ""} jvmToolchain(17) }
      dependencies { add("uiBuilderGenerator", files($generatorFiles)) }
      uiBuilderGeneration { sourceSets { $registrations } }
    """
        .trimIndent(),
    )
    write(
      "records.json",
      """{"schemaVersion":2,"module":"test","variant":"authored","components":[]}""",
    )
    model("First.uid", "First")
    model("Second.uid", "Second")
    git("add", "First.uid", "Second.uid")
  }

  private fun run(vararg tasks: String, fail: Boolean = false) =
    GradleRunner.create()
      .withProjectDir(root)
      .withPluginClasspath()
      .withArguments(*tasks, "--stacktrace", "--console=plain", "--max-workers=2")
      .let { if (fail) it.buildAndFail() else it.build() }

  @Test
  fun `JVM compilation owns outputs and always revalidates Git even with configuration cache`() {
    fixture()
    write("src/main/kotlin/Consumer.kt", "package example\nfun title() = First(\"hello\").title")
    val first = run("compileKotlin")
    assertEquals(TaskOutcome.SUCCESS, first.task(":generateUiBuilderMain")?.outcome)
    val output = root.resolve("build/generated/uiBuilder/main/kotlin")
    val original = output.resolve("example/First.kt").readText()
    val second = run("compileKotlin")
    assertContains(second.output, "Reusing configuration cache")
    assertEquals(TaskOutcome.SUCCESS, second.task(":generateUiBuilderMain")?.outcome)
    assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileKotlin")?.outcome)
    output.deleteRecursively()
    run("compileKotlin")
    assertEquals(original, output.resolve("example/First.kt").readText())
    // A local rename must delete the previous generated Kotlin file.
    model("Second.uid", "Renamed")
    run("compileKotlin")
    assertFalse(output.resolve("example/Second.kt").exists())
    assertTrue(output.resolve("example/Renamed.kt").isFile)
    // Removing an explicit entry also cleans its now-obsolete output.
    val build = root.resolve("build.gradle.kts")
    build.writeText(
      build
        .readText()
        .replace("entries.addAll(\"First.uid\", \"Second.uid\")", "entries.add(\"First.uid\")")
    )
    run("compileKotlin")
    assertFalse(output.resolve("example/Renamed.kt").exists())
    git("rm", "--cached", "First.uid")
    val untracked = run("compileKotlin", fail = true)
    assertContains(untracked.output, "Reusing configuration cache")
    assertContains(untracked.output, "UNTRACKED_INPUT")
    git("add", "First.uid")
    root.resolve("First.uid").delete()
    assertContains(run("compileKotlin", fail = true).output, "INPUT_OUTSIDE_PROJECT")
  }

  @Test
  fun `existing unowned output is preserved and blocks compilation`() {
    fixture()
    val protected = "build/generated/uiBuilder/main/kotlin/Handwritten.kt"
    write(protected, "// application-owned file")
    assertContains(
      run("compileKotlin", fail = true).output,
      "output directory is not owned by UID generation",
    )
    assertEquals("// application-owned file", root.resolve(protected).readText())
    assertFalse(root.resolve("build/generated/uiBuilder/main/kotlin/.uid-generated").exists())
  }

  @Test
  fun `Multiplatform explicitly wires common and platform source sets`() {
    fixture(
      multiplatform = true,
      registrations =
        """
      create("commonMain") { entries.add("First.uid"); records.from("records.json") }
      create("jvmMain") { entries.add("Second.uid"); records.from("records.json") }
    """,
    )
    write(
      "src/commonMain/kotlin/Common.kt",
      "package example\nfun common() = First(\"common\").title",
    )
    write("src/jvmMain/kotlin/Jvm.kt", "package example\nfun platform() = Second(common()).title")
    val result = run("compileKotlinJvm", "compileKotlinWasmJs", "--no-build-cache")
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateUiBuilderCommonMain")?.outcome)
    assertEquals(TaskOutcome.SUCCESS, result.task(":generateUiBuilderJvmMain")?.outcome)
    assertTrue(root.resolve("build/generated/uiBuilder/commonMain/kotlin/example/First.kt").isFile)
    assertFalse(
      root.resolve("build/generated/uiBuilder/commonMain/kotlin/example/Second.kt").exists()
    )
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlinWasmJs")?.outcome)
  }

  @Test
  fun `registration mistakes fail explicitly`() {
    fixture(
      registrations =
        "create(\"typo\") { entries.add(\"First.uid\"); records.from(\"records.json\") }"
    )
    assertContains(run("help", fail = true).output, "source set 'typo' does not exist")
    val build = root.resolve("build.gradle.kts")
    build.writeText(
      build
        .readText()
        .replace("create(\"typo\")", "create(\"main\")")
        .replace("entries.add(\"First.uid\")", "entries.set(emptyList())")
    )
    assertContains(run("compileKotlin", fail = true).output, "NO_ENTRY_FILES")
  }

  @Test
  fun `applying without registration does not resolve or generate sources`() {
    fixture(registrations = "")
    // No explicit generator dependency, and the default coordinate is deliberately unresolvable.
    val build = root.resolve("build.gradle.kts")
    build.writeText(
      build.readLines().filterNot { it.trim().startsWith("dependencies {") }.joinToString("\n") +
        "\nuiBuilderGeneration { generatorVersion.set(\"not-a-release\") }\n"
    )
    val result = run("compileKotlin", "generateUiBuilderSources")
    assertFalse(root.resolve("build/generated/uiBuilder").exists())
    assertEquals(null, result.task(":generateUiBuilderMain"))
  }
}
