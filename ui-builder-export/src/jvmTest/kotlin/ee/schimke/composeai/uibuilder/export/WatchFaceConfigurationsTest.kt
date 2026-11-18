package ee.schimke.composeai.uibuilder.export

import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A face with all three user configurations declared, and a scene that follows two of them: the XML
 * imports to typed nodes (nothing raw), exports back to the same tree, and the written XML is valid
 * at its version.
 */
class WatchFaceConfigurationsTest {
  private val xml =
    """
    <WatchFace width="450" height="450" clipShape="CIRCLE">
      <UserConfigurations>
        <ColorConfiguration id="theme" displayName="theme_label" defaultValue="ice">
          <ColorOption id="ice" displayName="ice_label" colors="#4FC3F7 #FF5252"/>
          <ColorOption id="ember" displayName="ember_label" colors="#FFA726 #26C6DA"/>
        </ColorConfiguration>
        <ListConfiguration id="look" displayName="look_label" defaultValue="plain">
          <ListOption id="plain" displayName="plain_label"/>
          <ListOption id="busy" displayName="busy_label"/>
        </ListConfiguration>
        <BooleanConfiguration id="reflections" displayName="reflections_label" defaultValue="TRUE"/>
      </UserConfigurations>
      <Scene backgroundColor="#ff000000">
        <BooleanConfiguration id="reflections">
          <BooleanOption id="TRUE">
            <PartDraw x="0" y="0" width="450" height="450">
              <Ellipse x="0" y="0" width="450" height="450"><Fill color="[CONFIGURATION.theme.1]"/></Ellipse>
            </PartDraw>
          </BooleanOption>
          <BooleanOption id="FALSE"><PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Fill color="#ff000000"/></Ellipse></PartDraw></BooleanOption>
        </BooleanConfiguration>
        <ListConfiguration id="look">
          <ListOption id="plain">
            <PartDraw x="0" y="0" width="450" height="450">
              <Ellipse x="0" y="0" width="450" height="450"><Fill color="[CONFIGURATION.theme.0]"/></Ellipse>
            </PartDraw>
          </ListOption>
          <ListOption id="busy"><PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Fill color="#ff000000"/></Ellipse></PartDraw></ListOption>
        </ListConfiguration>
      </Scene>
    </WatchFace>
    """
      .trimIndent()

  private val imported =
    assertIs<WatchFaceXmlImporter.Result.Imported>(
      WatchFaceXmlImporter.import(xml, WatchFaceXmlImporter.Options(wffVersion = 5)),
    )

  private fun count(component: String) =
    imported.document.nodes.values.count { it.componentId == component }

  @Test
  fun `every configuration and its scene container imports as a typed node, none raw`() {
    assertEquals(1, count(WatchFaceXmlExporter.COLOR_CONFIGURATION))
    assertEquals(2, count(WatchFaceXmlExporter.COLOR_OPTION))
    assertEquals(1, count(WatchFaceXmlExporter.LIST_CONFIGURATION))
    assertEquals(2, count(WatchFaceXmlExporter.LIST_OPTION))
    assertEquals(1, count(WatchFaceXmlExporter.BOOLEAN_CONFIGURATION))
    assertEquals(1, count(WatchFaceXmlExporter.SCENE_BOOLEAN_CONFIGURATION))
    assertEquals(2, count(WatchFaceXmlExporter.SCENE_BOOLEAN_OPTION))
    assertEquals(1, count(WatchFaceXmlExporter.SCENE_LIST_CONFIGURATION))
    assertEquals(2, count(WatchFaceXmlExporter.SCENE_LIST_OPTION))
    assertEquals(0, count(WatchFaceXmlExporter.RAW))
    assertTrue(imported.warnings.none { "raw" in it }, imported.warnings.toString())
  }

  @Test
  fun `the face round-trips to the same tree and validates against the format`() {
    val result = WatchFaceXmlExporter.export(imported.document)
    val written =
      assertIs<WatchFaceXmlExporter.Result.Emitted>(
          result,
          (result as? WatchFaceXmlExporter.Result.Refused)?.reasons.toString(),
        )
        .xml
    assertEquals(WffXmlParser.parse(xml), WffXmlParser.parse(written))
    val diagnostics = WffValidator.check(WffXmlParser.parse(written), WffVersion.V5)
    assertTrue(diagnostics.isEmpty(), diagnostics.toString())
  }
}
