package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.WearWidgetSample
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.helloWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.weatherWidgetUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The two worked Wear widget designs, checked against the catalog they claim.
 *
 * Validation is the whole point of the test: a template is the one document an author never wrote,
 * so nothing else would catch a property the catalog does not declare or a child a slot refuses —
 * it would open as an editor full of issues.
 */
class WearWidgetSampleTemplatesTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/remote-m3-capabilities-v1.json"))
  private val reference =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  /**
   * Each sample, host scaffold and all, validates against the `remote-m3` catalog it is offered
   * for.
   *
   * Its text is `remote-m3/remote-text`, the id the published catalog declares, rather than the
   * borrowed `m3/text`, which the published catalog does not: a sample naming it was refused as
   * `UNKNOWN_COMPONENT` on creation.
   */
  @Test
  fun `both samples validate against the remote-m3 catalog`() {
    WearWidgetSample.entries.forEach { sample ->
      val document = sample.document(sample.templateId, reference.catalogPin, reference.environment)

      val validation = CapabilityValidator(catalog).validate(document)

      assertTrue(
        validation.structurallyValid,
        "${sample.templateId}: ${validation.issues.joinToString { it.message }}",
      )
      assertTrue(
        document.nodes.values.none { it.componentId == "m3/text" },
        "${sample.templateId} borrows m3/text, which the published remote-m3 catalog does not have",
      )
    }
  }

  @Test
  fun `hello is the small host holding centred text on primary`() {
    val hello = helloWidgetUiBuilderDocument("hello", reference.catalogPin, reference.environment)

    val root = hello.nodes.getValue(hello.roots.single())
    assertEquals("remote-m3/widget-container-small", root.componentId)
    // The widget's own background is the CONTAINER's, because `WearWidgetContainer` paints it as
    // the round rect. A filled surface in the content slot would be a different picture.
    assertEquals(
      "primary",
      root.properties.getValue("background").jsonObject.getValue("value").jsonPrimitive.content,
    )
    val text = hello.nodes.getValue("hello-text")
    assertEquals(
      "Hello, World!",
      text.properties.getValue("text").jsonObject.getValue("value").jsonPrimitive.content,
    )
    assertEquals(
      "onPrimary",
      text.properties.getValue("color").jsonObject.getValue("value").jsonPrimitive.content,
    )
  }

  @Test
  fun `weather is the large host holding the sample's sunny colours`() {
    val weather =
      weatherWidgetUiBuilderDocument("weather", reference.catalogPin, reference.environment)

    val root = weather.nodes.getValue(weather.roots.single())
    assertEquals("remote-m3/widget-container-large", root.componentId)
    // ColorSunny from the sample, as a literal: the widget picks it by weather, not by theme.
    assertEquals(
      "#FF2196F3",
      root.properties.getValue("background").jsonObject.getValue("value").jsonPrimitive.content,
    )
    assertEquals(
      listOf("weather-location", "weather-reading"),
      weather.nodes.getValue("weather-column").slots.getValue("children"),
    )
  }

  /**
   * A blank widget declares no background, so the scaffold's own default has to be the one
   * `WearWidgetContainer` applies — nothing in the document says it.
   */
  @Test
  fun `an empty widget template declares no container properties at all`() {
    val small =
      wearWidgetUiBuilderDocument(
        "empty",
        reference.catalogPin,
        reference.environment,
        WearWidgetScaffoldSize.Small,
      )

    assertTrue(small.nodes.getValue(small.roots.single()).properties.isEmpty())
  }

  @Test
  fun `each sample is reachable by its template id and nothing else is`() {
    assertEquals(WearWidgetSample.Hello, WearWidgetSample.forTemplate("hello-widget"))
    assertEquals(WearWidgetSample.Weather, WearWidgetSample.forTemplate("weather-widget"))
    // The empty-scaffold templates keep their own path through the host; a sample must not claim
    // them, or "Small widget" would open the Hello design.
    assertEquals(null, WearWidgetSample.forTemplate("wear-widget-small"))
    assertEquals(null, WearWidgetSample.forTemplate("blank"))
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
