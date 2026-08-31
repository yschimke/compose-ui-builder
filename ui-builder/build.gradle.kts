plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

abstract class VerifyGeneratedSource : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val checkedIn: org.gradle.api.file.RegularFileProperty

  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val expected: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun verify() {
    check(checkedIn.get().asFile.readBytes().contentEquals(expected.get().asFile.readBytes())) {
      "Generated Jetcaster Compose is stale. Run ./gradlew :ui-builder:generateJetcasterComposeFixture"
    }
  }
}

val ktfmtCli = configurations.create("ktfmtCli")

dependencies { ktfmtCli(variantOf(libs.ktfmt.cli) { classifier("with-dependencies") }) }

kotlin {
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    outputModuleName.set("uiBuilder")
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.materialIconsExtended)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    getByName("jvmMain").dependencies {
      // Feasibility spike only: the saved-document bridge executes ComposeScene against SVGCanvas.
      // It remains NO-GO for production/Figma until a representative nested scene succeeds and the
      // adapter moves behind :render-host. currentOs supplies the matching local Skiko runtime.
      implementation(compose.desktop.currentOs)
    }
    getByName("jvmMain")
      .resources
      .srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    getByName("jvmTest") {
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    }
  }
}

tasks.register<JavaExec>("generateJetcasterComposeFixture") {
  description = "Generate the standalone Jetcaster Compose source from the frozen public document."
  group = "code generation"
  dependsOn("jvmMainClasses")
  classpath(
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("processedResources/jvm/main"),
    configurations.getByName("jvmRuntimeClasspath"),
  )
  mainClass.set("ee.schimke.composeai.uibuilder.GenerateJetcasterComposeFixture")
  args(
    rootProject.layout.projectDirectory
      .file(
        "ui-builder-generated-jetcaster/src/wasmJsMain/kotlin/generated/uibuilder/JetcasterDiscoverExpanded.kt"
      )
      .asFile
      .absolutePath
  )
}

val generatedJetcasterCheckFile =
  layout.buildDirectory.file("generated/ui-builder-check/JetcasterDiscoverExpanded.kt")

val generateJetcasterComposeFixtureForCheck =
  tasks.register<JavaExec>("generateJetcasterComposeFixtureForCheck") {
    description = "Generate Jetcaster Compose into build output for non-mutating verification."
    group = "verification"
    dependsOn("jvmMainClasses")
    classpath(
      layout.buildDirectory.dir("classes/kotlin/jvm/main"),
      layout.buildDirectory.dir("processedResources/jvm/main"),
      configurations.getByName("jvmRuntimeClasspath"),
    )
    mainClass.set("ee.schimke.composeai.uibuilder.GenerateJetcasterComposeFixture")
    args(generatedJetcasterCheckFile.get().asFile.absolutePath)
    outputs.file(generatedJetcasterCheckFile)
  }

val formatJetcasterComposeFixtureForCheck =
  tasks.register<JavaExec>("formatJetcasterComposeFixtureForCheck") {
    description = "Format the isolated generated fixture exactly like checked-in Kotlin."
    group = "verification"
    dependsOn(generateJetcasterComposeFixtureForCheck)
    classpath(ktfmtCli)
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--google-style", generatedJetcasterCheckFile.get().asFile.absolutePath)
    inputs.file(generatedJetcasterCheckFile)
    outputs.file(generatedJetcasterCheckFile)
  }

tasks.register<VerifyGeneratedSource>("checkJetcasterComposeFixture") {
  description = "Fail when the checked-in Jetcaster Compose fixture is stale."
  group = "verification"
  dependsOn(formatJetcasterComposeFixtureForCheck)
  checkedIn.set(
    rootProject.layout.projectDirectory.file(
      "ui-builder-generated-jetcaster/src/wasmJsMain/kotlin/generated/uibuilder/JetcasterDiscoverExpanded.kt"
    )
  )
  expected.set(generatedJetcasterCheckFile)
}

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the standalone Compose UI builder Wasm fixture."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
  from(rootProject.layout.projectDirectory.dir("wasm-ui/src/wasmJsMain/resources")) {
    include("js-joda.esm.js")
  }
  from(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder")) {
    include(
      "confetti-schedule-operations-v1.json",
      "jetcaster-discover-capabilities-v1.json",
      "jetcaster-discover-operations-v1.json",
    )
  }
  from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
    include("*.ttf", "fonts.json", "*OFL.txt", "LICENSE.txt")
    into("fonts")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
