package ee.schimke.composeai.uibuilder.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the `remote-m3` widget containers offer an author, checked against
 * `androidx.glance.wear.composable.WearWidgetContainer`.
 *
 * The container's whole authored surface is four parameters and a background brush chain. A
 * property or slot missing here is a widget an author cannot draw, and nothing else in the build
 * would notice: the catalog is synthesised in this module and the renderer reads it by name.
 */
class WearWidgetContainerCatalogTest {
  private val catalog =
    CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = linkedSetOf("m3-catalog", "remote-m3"))
      .listCatalogs()
      .single { it.benchmark.catalogSystemId == "remote-m3" }

  private val containers =
    catalog.components.filter { it.componentId.startsWith("remote-m3/widget-container-") }

  @Test
  fun `both containers declare WearWidgetContainer's four parameters`() {
    assertEquals(2, containers.size)
    containers.forEach { container ->
      assertEquals(
        listOf("background", "horizontalPaddingDp", "verticalPaddingDp", "cornerRadiusDp"),
        container.properties.map { it.name },
        container.componentId,
      )
      // None is required: an empty brush and the shipped 8/8/26 spec are what a widget that says
      // nothing gets, which is the container's own behaviour rather than a default this invented.
      assertTrue(container.properties.none { it.required }, container.componentId)
    }
  }

  /**
   * `WearWidgetBrush` is a chain of drawing elements, so the gradients and the image — the three
   * factories a string property cannot carry — are authored as nodes in a slot.
   */
  @Test
  fun `both containers accept a background brush chain of draw layers and images`() {
    containers.forEach { container ->
      val background = assertNotNull(container.slots.singleOrNull { it.name == "background" })
      assertEquals(0, background.cardinality.min, container.componentId)
      assertEquals(null, background.cardinality.max, container.componentId)
      assertEquals(listOf("DrawLayer", "ImageContent"), background.acceptedTraits)
      // Narrower than the content slot on purpose: a Text is not a brush, and upstream has no way
      // to express one as a background.
      assertTrue("AnyContent" !in background.acceptedTraits, container.componentId)
    }
  }

  /**
   * A slot narrowed to two traits is only a slot if the catalog has components carrying them.
   *
   * It did not: the reviewed `remote-m3` subset was six layout and text components, none of them a
   * brush, so the background could not be filled from the palette, from a drop, or from any
   * authored document the validator would accept, while `RemoteContentEmitter` was already writing
   * it (yschimke/compose-preview-server#428). `shape/linear-gradient` is the one the emitter turns
   * into a `WearWidgetBrush` chain; `asset/image` is the one it refuses by name, telling the author
   * which bitmap to supply in `provideWidgetData`.
   */
  @Test
  fun `the brushes the background slot names are in the catalog`() {
    val brushes = catalog.components.filter { it.componentId in BACKGROUND_BRUSHES }
    assertEquals(BACKGROUND_BRUSHES, brushes.map { it.componentId }.toSet())
    containers.forEach { container ->
      val background = assertNotNull(container.slots.singleOrNull { it.name == "background" })
      background.acceptedTraits.forEach { trait ->
        assertTrue(
          brushes.any { trait in it.traits },
          "${container.componentId}.background accepts $trait, which no brush here carries",
        )
      }
      brushes.forEach { brush ->
        assertTrue(slotAccepts(background, brush), "${brush.componentId} into background")
      }
    }
  }

  @Test
  fun `the content slot still takes a single widget body`() {
    containers.forEach { container ->
      val content = assertNotNull(container.slots.singleOrNull { it.name == "content" })
      assertEquals(1, content.cardinality.max, container.componentId)
      assertTrue("AnyContent" in content.acceptedTraits, container.componentId)
    }
  }

  private companion object {
    val BACKGROUND_BRUSHES = setOf("shape/linear-gradient", "asset/image")
  }
}
