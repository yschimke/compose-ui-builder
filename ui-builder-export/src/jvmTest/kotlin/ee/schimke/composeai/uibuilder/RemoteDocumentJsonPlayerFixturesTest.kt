package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive

/** The player proof reads exact output from the shared production exporter. */
class RemoteDocumentJsonPlayerFixturesTest {
  @Test
  fun `write mutable string JSON for the real player proof`() {
    val output = File("build/remote-json-strings").apply { mkdirs() }
    listOf("Ready", "Changed", "@second", "$" + "second", "", "Résumé 👋").forEachIndexed {
      index,
      value ->
      val result =
        assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
          RemoteDocumentJsonExporter.export(remoteJsonStringFixture(value))
        )
      File(output, "$index.json").writeText(result.source)
      File(output, "$index.expected.txt").writeText(value)
    }
  }

  @Test
  fun `write production JSON for the real player proof`() {
    val scenarios =
      mapOf(
        "Integer" to listOf(JsonPrimitive(10), JsonPrimitive(20)),
        "AdjacentInteger" to listOf(JsonPrimitive(16777216), JsonPrimitive(16777217)),
        "Extremes" to listOf(JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(Int.MAX_VALUE)),
        "Boolean" to listOf(JsonPrimitive(false), JsonPrimitive(true)),
        "ManyCases" to (10..130 step 10).map(::JsonPrimitive),
        "Float" to listOf(JsonPrimitive(1.25f), JsonPrimitive(2.5f)),
        "FloatAdjacent" to
          listOf(JsonPrimitive(1f), JsonPrimitive(Float.fromBits(1f.toBits() + 1))),
        "FloatSubnormal" to
          listOf(JsonPrimitive(Float.MIN_VALUE), JsonPrimitive(Float.MIN_VALUE * 2)),
        "FloatExtremes" to listOf(JsonPrimitive(-Float.MAX_VALUE), JsonPrimitive(Float.MAX_VALUE)),
      )
    val output = File("build/remote-json-selection").apply { mkdirs() }
    scenarios.forEach { (name, values) ->
      for (density in listOf(1, 2)) {
        val result =
          assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
            RemoteDocumentJsonExporter.export(remoteJsonSelectionFixture(values, density))
          )
        File(output, "$name-$density.json").writeText(result.source)
      }
    }
  }
}
