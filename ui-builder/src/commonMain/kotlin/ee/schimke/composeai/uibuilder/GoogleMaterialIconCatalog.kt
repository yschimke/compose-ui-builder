package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.vector.ImageVector

/** One generated Material icon binding shared by validation, rendering, selection and export. */
internal data class GoogleMaterialIcon(
  val key: String,
  val label: String,
  val composeExpression: String,
  /** False only for the original compatibility keys retained for saved designs. */
  val canonical: Boolean,
) {
  /** Resolve only icons that are actually painted; opening the picker must not allocate all 11k. */
  val imageVector: ImageVector
    get() =
      checkNotNull(generatedGoogleMaterialIconImageVector(key)) {
        "Generated Material icon '$key' has no ImageVector binding"
      }
}

/** Every style-qualified icon supplied by material-icons-extended, plus the original 46 aliases. */
internal val GoogleMaterialIcons: List<GoogleMaterialIcon> =
  GeneratedGoogleMaterialIcons.sortedWith(
    compareByDescending<GoogleMaterialIcon>(GoogleMaterialIcon::canonical)
      .thenBy(GoogleMaterialIcon::label)
      .thenBy(GoogleMaterialIcon::key)
  )

/** Picker rows exclude compatibility aliases, so one vector never appears twice. */
internal val SelectableGoogleMaterialIcons: List<GoogleMaterialIcon> =
  GoogleMaterialIcons.filter(GoogleMaterialIcon::canonical)

private val GoogleMaterialIconsByKey: Map<String, GoogleMaterialIcon> by lazy {
  GoogleMaterialIcons.associateBy(GoogleMaterialIcon::key)
}

internal fun googleMaterialIcon(key: String): GoogleMaterialIcon? = GoogleMaterialIconsByKey[key]
