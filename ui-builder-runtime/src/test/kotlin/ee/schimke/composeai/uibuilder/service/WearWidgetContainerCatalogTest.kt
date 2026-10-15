package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.REMOTE_CONTENT_COMPONENT_IDS
import ee.schimke.composeai.uibuilder.export.RemoteMaterial3
import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the `remote-m3` widget containers offer an author, checked against
 * `androidx.glance.wear.composable.WearWidgetContainer` (four parameters and a background brush
 * chain).
 */
class WearWidgetContainerCatalogTest {
  private val catalog =
    PublishedCatalogFixtures.executor(linkedSetOf("m3-catalog", "remote-m3"))
      .listCatalogs()
      .single { it.benchmark.catalogSystemId == "remote-m3" }

  private val containers =
    catalog.components.filter {
      it.componentId.startsWith("remote-m3/widget-container-") &&
        it.componentId != AdaptiveWearWidget.COMPONENT_ID
    }

  private val adaptive =
    catalog.components.single { it.componentId == AdaptiveWearWidget.COMPONENT_ID }

  @Test
  fun `both containers declare the widget's background, then its typefaces, and no host frame`() {
    assertEquals(2, containers.size)
    containers.forEach { container ->
      assertEquals(
        listOf("background") +
          ThemeTypefaces.WEAR_GROUPS.map { it.property } +
          ThemeTextStyle.PROPERTY,
        container.properties.map { it.name },
        container.componentId,
      )
      // None is required: an empty brush is what a widget that says nothing gets, which is the
      // container's own behaviour rather than a default this invented. Padding and radius are
      // absent on purpose — the host hands them in per shape (`WearWidgetParams`).
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
   * The background slot's two traits must be carried by catalog components: `shape/linear-gradient`
   * (a `WearWidgetBrush` chain) and `asset/image` (`WearWidgetBrush.image`, supplied by asset key
   * in `provideWidgetData`).
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
  fun `the adaptive container takes the same parameters and one slot per adaptive role`() {
    assertEquals(containers.first().properties, adaptive.properties)
    assertEquals(containers.first().role, adaptive.role)
    assertEquals(containers.first().traits, adaptive.traits)
    assertEquals(
      listOf("background") + AdaptiveWearWidget.CONTENT_SLOTS,
      adaptive.slots.map { it.name },
    )
    AdaptiveWearWidget.CONTENT_SLOTS.forEach { name ->
      val slot = adaptive.slots.single { it.name == name }
      // Any number of nodes, and only what the Remote Compose emitter can write.
      assertEquals(null, slot.cardinality.max, name)
      assertEquals(listOf("RemoteAuthorable"), slot.acceptedTraits, name)
    }
  }

  @Test
  fun `the content slot still takes a single widget body`() {
    containers.forEach { container ->
      val content = assertNotNull(container.slots.singleOrNull { it.name == "content" })
      assertEquals(1, content.cardinality.max, container.componentId)
      assertEquals(listOf("RemoteAuthorable"), content.acceptedTraits, container.componentId)
    }
  }

  /** A palette item must not be insertable into a widget body if its source emitter refuses it. */
  @Test
  fun `widget content accepts exactly the reviewed Remote Compose vocabulary`() {
    val content = containers.first().slots.single { it.name == "content" }
    val accepted =
      catalog.components.filter { slotAccepts(content, it) }.map { it.componentId }.toSet()

    assertTrue(accepted.isNotEmpty())
    assertEquals(emptySet(), accepted - REMOTE_CONTENT_COMPONENT_IDS)
    assertTrue("draw/canvas" in accepted)
    assertTrue("draw/rect" !in accepted)
    assertTrue("m3/surface" !in accepted)
    assertTrue("shape/linear-gradient" !in accepted)
  }

  /**
   * The Remote-only layouts accept only what the Remote emitter can write inside them, and every
   * Remote Material component can carry the Remote-only modifiers — a shared element on a button is
   * the ordinary case, and a priority on one inside a collapsible row.
   */
  @Test
  fun `remote-only layouts accept remote content and material components take the remote modifiers`() {
    val byId = catalog.components.associateBy { it.componentId }
    listOf(
        "layout/fit-box",
        "layout/flow-row",
        "layout/collapsible-column",
        "layout/collapsible-row",
      )
      .forEach { id ->
        byId.getValue(id).slots.forEach { slot ->
          assertEquals(listOf("RemoteAuthorable"), slot.acceptedTraits, "$id.${slot.name}")
        }
      }
    RemoteMaterial3.components.forEach { component ->
      val modifiers = byId.getValue(component.componentId).modifierCapabilities
      assertTrue("sharedElement" in modifiers, component.componentId)
      assertTrue("collapsiblePriority" in modifiers, component.componentId)
    }
  }

  private companion object {
    val BACKGROUND_BRUSHES = setOf("shape/linear-gradient", "asset/image")
  }
}
