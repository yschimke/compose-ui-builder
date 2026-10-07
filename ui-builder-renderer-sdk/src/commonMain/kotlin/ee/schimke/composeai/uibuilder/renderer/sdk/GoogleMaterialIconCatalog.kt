package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** One generated Material icon binding shared by validation, rendering, selection and export. */
data class GoogleMaterialIcon(
  val key: String,
  val label: String,
  val composeExpression: String,
  /** False only for the original compatibility keys retained for saved designs. */
  val canonical: Boolean,
)

/** Every style-qualified icon supplied by material-icons-extended, plus the original 46 aliases. */
val GoogleMaterialIcons: List<GoogleMaterialIcon> =
  GeneratedGoogleMaterialIcons.sortedWith(
    compareByDescending<GoogleMaterialIcon>(GoogleMaterialIcon::canonical)
      .thenBy(GoogleMaterialIcon::label)
      .thenBy(GoogleMaterialIcon::key)
  )

/** Picker rows exclude compatibility aliases, so one vector never appears twice. */
val SelectableGoogleMaterialIcons: List<GoogleMaterialIcon> =
  GoogleMaterialIcons.filter(GoogleMaterialIcon::canonical)

private val GoogleMaterialIconsByKey: Map<String, GoogleMaterialIcon> by lazy {
  GoogleMaterialIcons.associateBy(GoogleMaterialIcon::key)
}

fun googleMaterialIcon(key: String): GoogleMaterialIcon? = GoogleMaterialIconsByKey[key]

/**
 * Where an icon's `ImageVector` comes from.
 *
 * On the JVM, the compiled `material-icons-extended` builders: [cached] answers every key at once.
 * In the browser, [MaterialIconData] shards fetched beside the bundle: [cached] answers only what
 * has been loaded, and [load] fetches the rest. Keys are the catalog's, aliases included; an
 * unknown key is null on both.
 */
expect object GoogleMaterialIconVectors {
  /** The vector for [key] if it can be drawn right now, without waiting. */
  fun cached(key: String): ImageVector?

  /** The vector for [key], fetching its shard first where that is needed. */
  suspend fun load(key: String): ImageVector?
}

/** Resolve one authored icon key if it is ready now; see [rememberGoogleMaterialIconVector]. */
fun googleMaterialIconImageVector(key: String): ImageVector? =
  googleMaterialIcon(key)?.let { GoogleMaterialIconVectors.cached(it.key) }

/**
 * The vector for [key], or null while the browser is still fetching it.
 *
 * Answers in the first composition when the vector is already at hand — always on the JVM, so
 * renders and snapshots there are unchanged — and otherwise recomposes once it arrives. A caller
 * draws nothing, at the icon's size, for the null frames.
 */
@Composable
fun rememberGoogleMaterialIconVector(key: String): ImageVector? {
  var vector by remember(key) { mutableStateOf(googleMaterialIconImageVector(key)) }
  if (vector == null && googleMaterialIcon(key) != null) {
    LaunchedEffect(key) { vector = GoogleMaterialIconVectors.load(key) }
  }
  return vector
}
