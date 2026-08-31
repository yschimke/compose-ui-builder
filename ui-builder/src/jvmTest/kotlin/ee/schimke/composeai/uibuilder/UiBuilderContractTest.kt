package ee.schimke.composeai.uibuilder

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class UiBuilderContractTest {
  private val json = Json { encodeDefaults = true }

  @Test
  fun `shared public operations reduce to the cross-language document hash`() {
    val fixture = confettiFixture()
    val result = UiBuilderReducer.replay(fixture)
    val canonical = canonicalJson(json.parseToJsonElement(json.encodeToString(result.document)))

    assertEquals(57, result.document.revision)
    assertEquals(
      fixture.getValue("expectedDocumentHash").toString().trim('"'),
      canonical.sha256(),
    )
    assertEquals(
      listOf("chip-all", "chip-droidcon", "chip-swiftcon", "chip-fluttercon", "chip-reactcon"),
      result.document.nodes.getValue("track-filters").slots.getValue("items"),
    )
  }

  @Test
  fun `operation retries are idempotent`() {
    val fixture = confettiFixture()
    val operations = fixture.getValue("operations") as kotlinx.serialization.json.JsonArray
    val retried =
      JsonObject(
        fixture + ("operations" to kotlinx.serialization.json.JsonArray(operations + operations))
      )

    assertEquals(
      UiBuilderReducer.replay(fixture).document,
      UiBuilderReducer.replay(retried).document,
    )
  }

  @Test
  fun `stale insertion anchors fail instead of changing order`() {
    val fixture = confettiFixture()
    val operations = fixture.getValue("operations") as kotlinx.serialization.json.JsonArray
    val last = operations.last().jsonObject
    val invalid =
      JsonObject(last + ("afterNodeId" to kotlinx.serialization.json.JsonPrimitive("missing")))
    val altered =
      JsonObject(
        fixture +
          ("operations" to kotlinx.serialization.json.JsonArray(operations.dropLast(1) + invalid))
      )

    val error = assertFailsWith<IllegalArgumentException> { UiBuilderReducer.replay(altered) }
    assertTrue(error.message.orEmpty().contains("unknown insertion anchor"))
  }

  @Test
  fun `editor overlay is a sibling product and clean mode has design only`() {
    assertEquals(listOf(UiBuilderLayer.Design), uiBuilderLayers(editorOverlay = false))
    assertEquals(
      listOf(UiBuilderLayer.Design, UiBuilderLayer.EditorOverlay),
      uiBuilderLayers(editorOverlay = true),
    )
  }

  @Test
  fun `same reduced document exports recognizable Compose`() {
    val code = ComposeCodeExporter.export(UiBuilderReducer.replay(confettiFixture()).document)

    assertTrue(code.contains("fun ConfettiScheduleHeader()"))
    assertTrue(code.contains("Scaffold("))
    assertTrue(code.contains("CenterAlignedTopAppBar"))
    assertTrue(code.contains("Text(\"KotlinConf 2023\","))
    assertTrue(code.contains("LazyRow("))
    assertTrue(code.contains("FilterChip("))
    assertTrue(code.contains("Text(\"droidCon\")"))
    assertTrue(code.contains("Color(0xFF00FF4F)"))
    assertTrue(code.contains("Text(\"reactCon\")"))
    assertTrue(code.contains("PrimaryTabRow"))
    assertTrue(code.contains("Text(\"Thu 13 Apr\")"))
    assertTrue(code.contains("LazyColumn"))
    assertTrue(code.contains("ScheduleSessionItem("))
    assertTrue(code.contains("Confetti: building a Kotlin Multiplatform conference app"))
    assertTrue(code.contains("ScheduleBreak(title = \"Coffee Break\""))
  }

  @Test
  fun `Jetcaster public operations reduce to the cross-language document hash`() {
    val fixture = fixture("/jetcaster-discover-operations-v1.json")
    val result = UiBuilderReducer.replay(fixture)
    val canonical = canonicalJson(json.parseToJsonElement(json.encodeToString(result.document)))

    assertEquals(99, result.document.revision)
    assertEquals(99, result.document.nodes.size)
    assertEquals(listOf("root-surface"), result.document.roots)
    assertEquals(
      listOf("main-background"),
      result.document.nodes.getValue("pane-scaffold").slots.getValue("mainPane"),
    )
    assertEquals(
      listOf("detail-scaffold"),
      result.document.nodes.getValue("pane-scaffold").slots.getValue("supportingPane"),
    )
    assertEquals(
      fixture.getValue("expectedDocumentHash").toString().trim('"'),
      canonical.sha256(),
    )
  }

  private fun confettiFixture(): JsonObject = fixture("/confetti-schedule-operations-v1.json")

  private fun fixture(path: String): JsonObject {
    val resource = checkNotNull(javaClass.getResource(path))
    return json.parseToJsonElement(resource.readText()).jsonObject
  }
}

private fun String.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).joinToString("") {
    "%02x".format(it)
  }
