// `:ui-builder-export` — the projection from a saved design onto the screen the generator consumes.
//
// It exists because two very different callers need the same answer to one question — *what does
// this document mean?* — and until now only one of them could ask it. `:server` projects a saved
// `DesignDocumentV1` onto `ScreenDocument` and runs the real `ScreenGenerator`; the browser editor,
// which is wasm, could not reach that code at all and kept a hand-written emitter of its own. The
// two then disagreed about which designs export, which is the drift this module ends.
//
// **Kotlin Multiplatform** (jvm + wasmJs) for exactly that reason, and it is only possible because
// `ee.schimke.composeai:screen-model` publishes the generator for both targets. `preview-discovery`
// carries the same code for the JVM alone, which is why this could not be done before 1.77.0.
//
// **No Compose dependency.** This is projection and generation, not rendering: `:ui-builder` may
// depend on it without inverting anything, and `:server` may without pulling Compose UI onto a
// server classpath.
plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

ktfmt { googleStyle() }

kotlin {
  jvm()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      api(libs.composeai.screen.model)
      api(libs.composeai.ui.builder.protocol)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
