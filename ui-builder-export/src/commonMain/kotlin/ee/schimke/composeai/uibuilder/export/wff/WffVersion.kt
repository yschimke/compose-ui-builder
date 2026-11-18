// Vendored from yschimke/watchface-tools wff-core (commit f1d35e9):
//   wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffVersion.kt
// Only visibility (public -> internal) and the package differ.
// TODO: replace with ee.schimke.watchface:wff-core once published
package ee.schimke.composeai.uibuilder.export.wff

/** Watch Face Format versions, as declared by `com.google.wear.watchface.format.version`. */
internal enum class WffVersion(internal val number: Int) {
  V1(1),
  V2(2),
  V3(3),
  V4(4),
  V5(5);

  internal companion object {
    internal val LATEST: WffVersion = V5

    internal fun fromNumber(n: Int): WffVersion? = entries.firstOrNull { it.number == n }
  }
}
