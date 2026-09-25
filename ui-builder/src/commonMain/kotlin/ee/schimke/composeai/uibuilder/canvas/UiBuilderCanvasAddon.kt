package ee.schimke.composeai.uibuilder.canvas

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.CanvasAdapterRegistry

/**
 * The in-process canvas's API for add-ons: what a module drawing another catalog's components may
 * read from `:ui-builder` without the builder knowing the catalog.
 *
 * Opt-in because it is a seam, not a product API. It exists so a catalog's canvas can live in its
 * own module — the Wear one in `:ui-builder-canvas-wear` — while hosts that cannot load a catalog's
 * Wasm renderer runtime (the desktop app, the IntelliJ plugin, the server's render bundle) still
 * draw it. The browser editor draws such catalogs through their published runtime instead.
 */
@RequiresOptIn(
  message = "UI-builder canvas add-on API: for modules that draw a catalog in-process.",
  level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS,
)
annotation class UiBuilderCanvasAddonApi

/**
 * A catalog's in-process canvas, added beside the built-in Material 3 one.
 *
 * Material 3 is the one catalog `:ui-builder` draws itself. Anything else reaches the in-process
 * canvas through one of these: its [adapters] are consulted before the built-in table, and the
 * three root hooks let a catalog whose root is a device frame (a round watch screen, a widget host)
 * set the surface around it — the only things an adapter cannot do from inside its own node.
 */
@UiBuilderCanvasAddonApi
interface UiBuilderCanvasAddon {
  /** Component and frame adapters, by adapter id. */
  val adapters: CanvasAdapterRegistry

  /** The colour scheme for a design whose single root draws with [rootAdapterId], or null. */
  fun rootColorScheme(rootAdapterId: String): ColorScheme? = null

  /** The theme corner radius for a design whose single root draws with [rootAdapterId]. */
  fun rootCornerRadiusDp(rootAdapterId: String): Float? = null

  /** Where a top-level node drawn with [adapterId] sits in the surface, or null for the default. */
  fun rootAlignment(adapterId: String): Alignment? = null

  /**
   * Locals this add-on provides around [document] when the catalog declares [platform] — a watch
   * device configuration for a Wear catalog, for instance.
   */
  @Composable
  fun surfaceLocals(document: UiBuilderDocument, platform: String): Array<ProvidedValue<*>> =
    emptyArray()
}

/**
 * The add-ons the in-process canvas draws with. Defaults to the ones on the platform's classpath —
 * `ServiceLoader` on the JVM, none in the browser — so a host adds a catalog by adding its module.
 */
@UiBuilderCanvasAddonApi
val LocalUiBuilderCanvasAddons = staticCompositionLocalOf { platformCanvasAddons() }

/** The canvas add-ons this platform finds without being told. */
@UiBuilderCanvasAddonApi internal expect fun platformCanvasAddons(): List<UiBuilderCanvasAddon>

/** The document the surface is drawing, for an adapter whose geometry depends on it. */
@UiBuilderCanvasAddonApi
val LocalUiBuilderSurfaceDocument = staticCompositionLocalOf<UiBuilderDocument?> { null }
