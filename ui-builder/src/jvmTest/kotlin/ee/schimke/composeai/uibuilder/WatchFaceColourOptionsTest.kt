package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.watchFaceColourConfigurationReferences
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A face's own colour options are what a Fill or Stroke offers in place of a theme role. */
class WatchFaceColourOptionsTest {
  private fun import(xml: String) =
    assertIs<WatchFaceXmlImporter.Result.Imported>(WatchFaceXmlImporter.import(xml)).document

  @Test
  fun `each colour slot of a face's colour configuration is offered as a reference`() {
    val document =
      import(
        """
        <WatchFace width="450" height="450">
          <UserConfigurations>
            <ColorConfiguration id="theme" displayName="Theme" defaultValue="warm">
              <ColorOption id="warm" displayName="Warm" colors="#ffff8800 #ffffcc00"/>
              <ColorOption id="cool" displayName="Cool" colors="#ff0088ff #ff00ccff"/>
            </ColorConfiguration>
          </UserConfigurations>
          <Scene><Group name="g" x="0" y="0" width="450" height="450"/></Scene>
        </WatchFace>
        """
          .trimIndent(),
      )
    assertEquals(
      listOf("[CONFIGURATION.theme.0]", "[CONFIGURATION.theme.1]"),
      watchFaceColourConfigurationReferences(document),
    )
  }

  @Test
  fun `a face with no colour configuration offers none`() {
    val document =
      import(
        """<WatchFace width="450" height="450"><Scene><Group name="g" x="0" y="0" width="450" height="450"/></Scene></WatchFace>""",
      )
    assertEquals(emptyList(), watchFaceColourConfigurationReferences(document))
  }
}
