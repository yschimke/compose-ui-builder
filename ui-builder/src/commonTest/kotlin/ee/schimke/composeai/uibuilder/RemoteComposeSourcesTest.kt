package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteComposeSourcesTest {
  private val previews =
    """
    {
      "module": "remote-m3",
      "previews": [
        {
          "id": "appcard__ideal__default__compact",
          "label": "App card",
          "modes": ["snapshot"],
          "remoteCompose": true
        },
        {
          "id": "button__ideal__filled__compact",
          "label": "Filled button",
          "modes": ["snapshot"],
          "remoteCompose": true
        },
        {
          "id": "appcard__ideal__icon__compact",
          "label": "App card with icon",
          "modes": ["snapshot"],
          "remoteCompose": true
        },
        {
          "id": "wearcompose__button__compact",
          "label": "A Jetpack Compose preview",
          "modes": ["snapshot", "live"]
        }
      ]
    }
    """
      .trimIndent()

  @Test
  fun `only previews publishing a document become sources`() {
    val sources = parseRemoteComposeSources(previews)

    assertEquals(
      listOf(
        "appcard__ideal__default__compact",
        "appcard__ideal__icon__compact",
        "button__ideal__filled__compact",
      ),
      sources.map(RemoteComposeSource::id),
    )
    // Grouped by the sticker id's first segment, and ordered by group then label, so the palette's
    // headings are the component families rather than the order the catalog happened to list.
    assertEquals(listOf("appcard", "appcard", "button"), sources.map(RemoteComposeSource::group))
    assertEquals("App card", sources.first().label)
  }

  @Test
  fun `an unknown field does not empty the palette`() {
    val sources =
      parseRemoteComposeSources(
        """
        {"previews": [{"id": "a__b", "label": "A", "remoteCompose": true, "somethingNew": 3}]}
        """
          .trimIndent()
      )

    assertEquals(listOf("a__b"), sources.map(RemoteComposeSource::id))
  }

  @Test
  fun `a preview id with no family still lists`() {
    val sources =
      parseRemoteComposeSources("""{"previews": [{"id": "solo", "remoteCompose": true}]}""")

    assertEquals(listOf(RemoteComposeSource("solo", "solo", "documents")), sources)
  }

  @Test
  fun `search matches label id and group`() {
    val sources = parseRemoteComposeSources(previews)

    assertEquals(sources, filterRemoteComposeSources(sources, "  "))
    assertEquals(
      listOf("button__ideal__filled__compact"),
      filterRemoteComposeSources(sources, "Filled").map(RemoteComposeSource::id),
    )
    assertEquals(2, filterRemoteComposeSources(sources, "appcard").size)
    assertTrue(filterRemoteComposeSources(sources, "nothing here").isEmpty())
  }
}
