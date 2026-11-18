// Vendored from yschimke/watchface-tools wff-core 0.2.1
// (wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffVersion.kt).
// Only visibility (public -> internal) and the package differ. wff-core 0.2.1 is compiled for Java
// 21 bytecode;
// this repository's server floor is Java 17, so it cannot be consumed by coordinate yet.

package ee.schimke.composeai.uibuilder.export.wff

/** Watch Face Format versions, as declared by `com.google.wear.watchface.format.version`. */
internal enum class WffVersion(val number: Int) {
  V1(1),
  V2(2),
  V3(3),
  V4(4),
  V5(5);

  companion object {
    internal val LATEST: WffVersion = V5

    internal fun fromNumber(n: Int): WffVersion? = entries.firstOrNull { it.number == n }
  }
}
