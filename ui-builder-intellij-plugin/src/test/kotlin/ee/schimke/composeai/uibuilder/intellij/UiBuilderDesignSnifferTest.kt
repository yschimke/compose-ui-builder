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
}
