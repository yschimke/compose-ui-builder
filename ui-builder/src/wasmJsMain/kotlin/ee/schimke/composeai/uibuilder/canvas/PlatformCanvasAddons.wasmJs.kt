package ee.schimke.composeai.uibuilder.canvas

/**
 * None: the browser editor draws a catalog it does not build in through that catalog's published
 * renderer runtime, not through an in-process add-on.
 */
@UiBuilderCanvasAddonApi
internal actual fun platformCanvasAddons(): List<UiBuilderCanvasAddon> = emptyList()
