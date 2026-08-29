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
  dependsOn(":server:check", ":usage-source-psi:check", ":wasm-ui:check")
}

tasks.register("ktfmtCheckAll") {
  group = "verification"
  dependsOn(":server:ktfmtCheck", ":usage-source-psi:ktfmtCheck", ":wasm-ui:ktfmtCheck")
}

tasks.register("ktfmtFormat") {
  group = "formatting"
  dependsOn(":server:ktfmtFormat", ":usage-source-psi:ktfmtFormat", ":wasm-ui:ktfmtFormat")
}
