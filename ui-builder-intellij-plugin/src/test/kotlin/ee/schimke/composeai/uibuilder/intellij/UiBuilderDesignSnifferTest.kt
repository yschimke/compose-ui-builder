package ee.schimke.composeai.uibuilder.intellij

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiBuilderDesignSnifferTest {
  @Test
  fun readsTheDeclaredSchema() {
    assertEquals(
      "compose-ui-builder-document/v1",
      sniffDesignSchema("""{ "schema" : "compose-ui-builder-document/v1", "id": "d" }"""),
    )
  }

  @Test
  fun ignoresOrdinaryJson() {
    assertNull(sniffDesignSchema("""{ "name": "app", "version": "1.0.0" }"""))
  }

  @Test
  fun recognisesTheCheckedInSmokeDesignWithinTheWindow() {
    val head =
      File("src/integrationTest/resources/smoke-project/smoke.uid")
        .readBytes()
        .take(DESIGN_HEADER_SNIFF_BYTES)
        .toByteArray()
        .decodeToString()

    assertEquals("compose-ui-builder-document/v1-candidate", sniffDesignSchema(head))
  }

  @Test
  fun findsASchemaDeclaredPastTheHeadWindow() {
    // Key order means nothing in JSON: a valid design may carry its `schema` after 4 KiB of nodes.
    val late =
      "{ \"nodes\": \"" +
        "x".repeat(DESIGN_HEADER_SNIFF_BYTES * 3) +
        "\", " +
        "\"schema\": \"compose-ui-builder-document/v1\" }"

    assertNull(sniffDesignSchema(late.take(DESIGN_HEADER_SNIFF_BYTES)))
    assertEquals(
      "compose-ui-builder-document/v1",
      scanForDesignSchema(late.byteInputStream()),
    )
  }

  @Test
  fun findsADeclarationSplitAcrossReadChunks() {
    // Placed to straddle the scanner's 64 KiB chunk boundary.
    val padding = "y".repeat(64 * 1024 - 10)
    val text = "{ \"a\": \"$padding\", \"schema\": \"compose-ui-builder-document/v1\" }"

    assertEquals("compose-ui-builder-document/v1", scanForDesignSchema(text.byteInputStream()))
  }

  @Test
  fun anUnrelatedSchemaKeyDoesNotClaimAFile() {
    val text = "{ \"schema\": \"https://json.schemastore.org/package\", \"name\": \"app\" }"

    assertNull(scanForDesignSchema(text.byteInputStream()))
  }

  @Test
  fun theScanStopsAtItsLimit() {
    val text = "z".repeat(1024) + "\"schema\": \"compose-ui-builder-document/v1\""

    assertNull(scanForDesignSchema(text.byteInputStream(), limit = 512))
  }
}
