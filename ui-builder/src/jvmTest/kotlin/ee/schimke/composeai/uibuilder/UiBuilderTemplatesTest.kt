package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderTemplatesTest {
  @Test
  fun `blank template is a valid minimal scaffold with an empty content container`() {
    val fixture =
      Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
    val reference = UiBuilderReducer.replay(fixture).document
    val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))

    val blank = blankUiBuilderDocument("from-scratch", reference.catalogPin, reference.environment)

    assertEquals(0, blank.revision)
    assertEquals(listOf("screen-scaffold"), blank.roots)
    assertEquals("layout/scaffold", blank.nodes.getValue("screen-scaffold").componentId)
    assertEquals(
      listOf("screen-content"),
      blank.nodes.getValue("screen-scaffold").slots.getValue("content"),
    )
    assertEquals("layout/box", blank.nodes.getValue("screen-content").componentId)
    assertTrue(blank.nodes.getValue("screen-content").slots.getValue("children").isEmpty())
    val validation = CapabilityValidator(catalog).validate(blank)
    assertTrue(validation.structurallyValid, validation.issues.joinToString { it.message })
  }

  @Test
  fun `wear widget templates copy the two stable host preview canvases as slot ready scaffolds`() {
    val fixture =
      Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
    val reference = UiBuilderReducer.replay(fixture).document

    val small =
      wearWidgetUiBuilderDocument(
        "small-widget",
        reference.catalogPin,
        reference.environment,
        WearWidgetScaffoldSize.Small,
      )
    val large =
      wearWidgetUiBuilderDocument(
        "large-widget",
        reference.catalogPin,
        reference.environment,
        WearWidgetScaffoldSize.Large,
      )

    assertEquals(
      "remote-m3/widget-container-small",
      small.nodes.getValue(small.roots.single()).componentId,
    )
    assertEquals(
      "remote-m3/widget-container-large",
      large.nodes.getValue(large.roots.single()).componentId,
    )
    assertEquals("Wear widget · Small (216×76dp)", small.title)
    assertEquals("Wear widget · Large (216×124dp)", large.title)
    assertTrue(small.nodes.getValue(small.roots.single()).slots.getValue("content").isEmpty())
    assertTrue(large.nodes.getValue(large.roots.single()).slots.getValue("content").isEmpty())
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `a new design can declare the state its screen will react to`() {
    val reference =
      UiBuilderReducer.replay(
          Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
        )
        .document
    val blank =
      blankUiBuilderDocument(
        "from-scratch",
        reference.catalogPin,
        reference.environment,
        listOf(
          NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false)),
          NewDesignState("caption", NewDesignStateType.Text, JsonPrimitive("")),
        ),
      )

    assertEquals(setOf("expanded", "caption"), blank.stateVariables.keys)
    val expanded = blank.stateVariables.getValue("expanded").jsonObject
    assertEquals("value", expanded.getValue("type").jsonPrimitive.content)
    assertEquals("bool", expanded.getValue("valueType").jsonPrimitive.content)
    // `preview` is the only persistence a browser-authored design can honestly claim; anything
    // durable is a promise about a host this document knows nothing about.
    assertEquals("preview", expanded.getValue("persistence").jsonPrimitive.content)
  }

  @Test
  fun `a blank design still declares no state when none is asked for`() {
    val reference =
      UiBuilderReducer.replay(
          Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
        )
        .document
    val blank = blankUiBuilderDocument("from-scratch", reference.catalogPin, reference.environment)

    assertEquals(emptyMap(), blank.stateVariables)
  }

  @Test
  fun `two state variables cannot share a name`() {
    val reference =
      UiBuilderReducer.replay(
          Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
        )
        .document
    assertFailsWith<IllegalArgumentException> {
      blankUiBuilderDocument(
        "from-scratch",
        reference.catalogPin,
        reference.environment,
        listOf(
          NewDesignState("expanded", NewDesignStateType.Flag, JsonPrimitive(false)),
          NewDesignState("expanded", NewDesignStateType.Text, JsonPrimitive("")),
        ),
      )
    }
  }
}
