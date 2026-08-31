plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.maven.publish) apply false
}

tasks.named("check") {
  group = "verification"
  dependsOn(
    ":render-host:check",
    ":server:check",
    ":slot-preview-runtime:check",
    ":ui-builder:check",
    ":ui-builder-generated-jetcaster:check",
    ":ui-builder-reference-jetcaster:check",
    ":usage-source-psi:check",
    ":wasm-ui:check",
  )
}

tasks.register("ktfmtCheckAll") {
  group = "verification"
  dependsOn(
    ":render-host:ktfmtCheck",
    ":server:ktfmtCheck",
    ":slot-preview-runtime:ktfmtCheck",
    ":ui-builder:ktfmtCheck",
    ":ui-builder-generated-jetcaster:ktfmtCheck",
    ":ui-builder-reference-jetcaster:ktfmtCheck",
    ":usage-source-psi:ktfmtCheck",
    ":wasm-ui:ktfmtCheck",
  )
}

tasks.register("ktfmtFormat") {
  group = "formatting"
  dependsOn(
    ":render-host:ktfmtFormat",
    ":server:ktfmtFormat",
    ":slot-preview-runtime:ktfmtFormat",
    ":ui-builder:ktfmtFormat",
    ":ui-builder-generated-jetcaster:ktfmtFormat",
    ":ui-builder-reference-jetcaster:ktfmtFormat",
    ":usage-source-psi:ktfmtFormat",
    ":wasm-ui:ktfmtFormat",
  )
}
