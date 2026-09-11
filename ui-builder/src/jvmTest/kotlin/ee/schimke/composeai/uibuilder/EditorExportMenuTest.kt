package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The Export menu is a function of what the catalog can render, and that is the thing to pin: a
 * menu that offered SVG to a catalog whose renderer cannot draw it would be a row that always
 * fails, and a menu that hid PNG from one that can would be the export lane nobody could reach.
 */
class EditorExportMenuTest {

  @Test
  fun `remote source and document downloads need no image renderer`() {
    val formats = exportFormatsFor(svg = false, png = false, json = true, rc = true)
    val entries = exportMenuEntries(formats).flatten()
    assertEquals(
      if (UiBuilderBuildFeatures.remoteCompose)
        listOf(EditorExportFormat.Json, EditorExportFormat.Rc)
      else emptyList(),
      formats,
    )
    assertEquals(UiBuilderBuildFeatures.remoteCompose, entries.any { it.label == "Copy JSON" })
    assertTrue(
      entries.none { it is EditorExportMenuEntry.CopyPicture && it.format == EditorExportFormat.Rc }
    )
    assertEquals(UiBuilderBuildFeatures.remoteCompose, entries.any { it.label == "Download JSON" })
    assertEquals(
      UiBuilderBuildFeatures.remoteCompose,
      entries.any { it.label == "Download Remote document (.rc)" },
    )
  }

  @Test
  fun `the menu lists every verb for every format the catalog can render, verb by verb`() {
    val groups = exportMenuEntries(exportFormatsFor(svg = true, png = true))

    assertEquals(
      listOf(
        listOf("Copy SVG", "Copy PNG"),
        listOf("Copy SVG link", "Copy PNG link"),
        listOf("Download SVG", "Download PNG"),
      ),
      groups.map { group -> group.map { it.label } },
    )
  }

  @Test
  fun `a catalog without svg keeps every png row and loses every svg row`() {
    val groups = exportMenuEntries(exportFormatsFor(svg = false, png = true))

    assertEquals(
      listOf(listOf("Copy PNG"), listOf("Copy PNG link"), listOf("Download PNG")),
      groups.map { group -> group.map { it.label } },
    )
  }

  @Test
  fun `a catalog that renders nothing has no menu at all`() {
    assertEquals(emptyList(), exportFormatsFor(svg = false, png = false))
    assertEquals(emptyList(), exportMenuEntries(emptyList()))
  }

  @Test
  fun `svg leads because the Figma route is what the menu is for`() {
    val entries = exportMenuEntries(exportFormatsFor(svg = true, png = true)).flatten()
    assertEquals(EditorExportFormat.Svg, entries.first().format)
    val copySvg = entries.first { it.label == "Copy SVG" }
    assertTrue("Figma" in copySvg.detail, copySvg.detail)
    val link = entries.first { it.label == "Copy SVG link" }
    assertTrue("live" in link.detail, link.detail)
  }

  @Test
  fun `each row reaches the host verb it names`() {
    val calls = mutableListOf<String>()
    val host =
      object : UiBuilderExportHost {
        override val formats = exportFormatsFor(svg = true, png = true)

        override suspend fun copyPicture(format: EditorExportFormat): String =
          "picture:${format.extension}".also(calls::add)

        override suspend fun copyLink(format: EditorExportFormat): String =
          "link:${format.extension}".also(calls::add)

        override suspend fun download(format: EditorExportFormat): String =
          "download:${format.extension}".also(calls::add)
      }

    val answers = runBlocking {
      exportMenuEntries(host.formats).flatten().map { entry -> host.perform(entry) }
    }

    assertEquals(
      listOf(
        "picture:svg",
        "picture:png",
        "link:svg",
        "link:png",
        "download:svg",
        "download:png",
      ),
      answers,
    )
    assertEquals(answers, calls)
  }
}
