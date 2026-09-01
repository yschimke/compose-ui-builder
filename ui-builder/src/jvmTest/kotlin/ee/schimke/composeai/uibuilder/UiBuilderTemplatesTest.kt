package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class UiBuilderTemplatesTest {
  @Test
  fun `blank template is a valid minimal scaffold with an empty content container`() {
    val fixture =
      Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
    val reference = UiBuilderReducer.replay(fixture).document
    val catalog =
      CapabilityCatalogParser.parse(resource("/jetcaster-discover-capabilities-v1.json"))

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

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
