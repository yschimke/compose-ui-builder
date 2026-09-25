package ee.schimke.composeai.uibuilder.canvas

import java.util.ServiceLoader

/**
 * Every [UiBuilderCanvasAddon] a `META-INF/services` entry names on this classpath, loaded once.
 *
 * Through the add-on interface's own class loader rather than the thread's: inside the IntelliJ
 * plugin the context class loader is the IDE's, which cannot see the plugin's jars.
 */
@UiBuilderCanvasAddonApi
private val discovered: List<UiBuilderCanvasAddon> by lazy {
  ServiceLoader.load(UiBuilderCanvasAddon::class.java, UiBuilderCanvasAddon::class.java.classLoader)
    .toList()
}

@UiBuilderCanvasAddonApi
internal actual fun platformCanvasAddons(): List<UiBuilderCanvasAddon> = discovered
