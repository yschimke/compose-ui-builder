package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.CatalogRuntimePreviewEngine
import ee.schimke.composeai.uibuilder.editor.CatalogRuntimePreviewEngine.Engine
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CatalogRuntimePreviewEngineTest {
  private val design =
    wearWidgetUiBuilderDocument(
      designId = "widget",
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(mapOf("density" to JsonPrimitive(2f))),
      size = WearWidgetScaffoldSize.Large,
    )
  private val offering = setOf(CatalogRuntimePreviewEngine.CAPABILITY)

  @AfterTest
  fun reset() {
    CatalogRuntimePreviewEngine.persist = {}
    CatalogRuntimePreviewEngine.select(null)
  }

  @Test
  fun `the chosen engine rides in the environment of the document sent`() {
    CatalogRuntimePreviewEngine.select(Engine.Live)

    val sent = CatalogRuntimePreviewEngine.applyTo(design, offering)

    assertEquals(
      JsonPrimitive("live"),
      sent.environment[CatalogRuntimePreviewEngine.ENVIRONMENT_KEY],
    )
    assertEquals(design.environment["density"], sent.environment["density"])
  }

  @Test
  fun `a runtime that does not offer engines is sent the design unchanged`() {
    CatalogRuntimePreviewEngine.select(Engine.Capture)

    assertSame(design, CatalogRuntimePreviewEngine.applyTo(design, setOf("revealNode")))
  }

  @Test
  fun `the runtime default sends nothing`() {
    assertSame(design, CatalogRuntimePreviewEngine.applyTo(design, offering))
  }

  @Test
  fun `a choice is persisted, and resetting it clears it`() {
    val stored = mutableListOf<Engine?>()
    CatalogRuntimePreviewEngine.persist = { stored += it }

    CatalogRuntimePreviewEngine.select(Engine.Live)
    CatalogRuntimePreviewEngine.select(null)

    assertEquals(listOf(Engine.Live, null), stored)
    assertNull(CatalogRuntimePreviewEngine.selected)
  }

  @Test
  fun `engine ids parse, and anything else is no choice`() {
    assertEquals(Engine.Capture, CatalogRuntimePreviewEngine.parse("capture"))
    assertEquals(Engine.Live, CatalogRuntimePreviewEngine.parse("live"))
    assertNull(CatalogRuntimePreviewEngine.parse("fast"))
    assertNull(CatalogRuntimePreviewEngine.parse(""))
  }
}
