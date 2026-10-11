package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json

/**
 * A published `ui-builder.json` comes from another repository over the network and is read at
 * startup unreviewed. This pins that no document makes `compose` throw: an exception escapes into
 * `openUiBuilderLane` and disables the UI builder for every catalog on the host, the blast radius
 * the per-catalog fallback exists to prevent. Specific refusals are in
 * [PublishedUiBuilderCatalogTest].
 */
class PublishedUiBuilderCatalogHostileInputTest {

  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  private val record = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<ComponentRecordFile>(
      """
      {
        "schemaVersion": 1,
        "module": ":hostile",
        "variant": "debug",
        "components": [
          {
            "canonicalId": ":hostile/com.example.AKt.Widget",
            "componentIds": ["Widgets/Widget"],
            "symbol": {
              "name": "Widget",
              "callable": "com.example.Widget",
              "jvmOwner": "com.example.AKt",
              "origin": "PROJECT"
            },
            "parameters": [
              { "name": "label", "type": "String", "typeFqn": "kotlin.String", "hasDefault": false }
            ],
            "slots": [],
            "code": { "imports": ["com.example.Widget"] }
          },
          {
            "canonicalId": ":hostile/com.example.AKt.Nameless",
            "componentIds": [],
            "symbol": {
              "name": "",
              "callable": "com.example.",
              "jvmOwner": "com.example.AKt",
              "origin": "PROJECT"
            },
            "parameters": [],
            "slots": [],
            "code": { "imports": [] }
          }
        ]
      }
      """
        .trimIndent()
    )

  /**
   * Documents a generator would never write but a mistake or attacker could. None should compose;
   * all must return.
   */
  private val hostile: List<Pair<String, String>> =
    listOf(
      "empty string" to "",
      "whitespace" to "   \n  ",
      "not JSON at all" to "<!doctype html><title>404</title>",
      "a JSON array" to "[]",
      "a JSON string" to "\"ui-builder\"",
      "a JSON number" to "17",
      "null" to "null",
      "an empty object" to "{}",
      "the right schema and nothing else" to """{"schema":"compose-ui-builder-catalog/v1"}""",
      "statusSemantics is a string" to
        """{"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},"statusSemantics":"no"}""",
      "componentIdPrefix is a number" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":4}}
        """
          .trimIndent(),
      "componentIdPrefix is only whitespace" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"   "}}
        """
          .trimIndent(),
      "a prefix that is a path traversal" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"../../../etc/"}}
        """
          .trimIndent(),
      // The two shapes that decide what `taken` and `skipped` hold.
      "a policy for a record component that does not exist" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2},
         "statusSemantics":{"componentIdPrefix":"h/",
           "components":{"h/ghost":{"record":":hostile/com.example.AKt.Missing"}}}}
        """
          .trimIndent(),
      "a builtin that collides with a derived component id" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2},
         "statusSemantics":{"componentIdPrefix":"h/",
           "builtins":{"h/widget":{"role":"screen-root"}}}}
        """
          .trimIndent(),
      "a declared id colliding with another component's derived id" to COLLIDING,
      "a record count far larger than the record" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":2147483647},
         "statusSemantics":{"componentIdPrefix":"h/"}}
        """
          .trimIndent(),
      "a negative record count" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "record":{"file":"components.json","schemaVersion":1,"components":-1},
         "statusSemantics":{"componentIdPrefix":"h/"}}
        """
          .trimIndent(),
      // `components` decodes as a Map; `.github/scripts/ui-builder-equivalence.sh` refuses the same
      // shapes, and the two must agree.
      "components is an array" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"h/","components":[]}}
        """
          .trimIndent(),
      "components is null" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"h/","components":null}}
        """
          .trimIndent(),
      "a builtin whose role is unknown" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"h/",
           "builtins":{"h/x":{"role":"a-role-invented-two-releases-from-now"}}}}
        """
          .trimIndent(),
      "deeply nested statusSemantics" to
        """
        {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
         "statusSemantics":{"componentIdPrefix":"h/","frame":${nest(200)}}}
        """
          .trimIndent(),
    )

  @Test
  fun `no published document makes the reader throw`() {
    // Both with and without a record: the record is the other half of the composition, and a
    // document that survives one may not survive the other.
    hostile.forEach { (label, document) ->
      listOf("with a record" to record, "with no record" to null).forEach { (arm, inventory) ->
        val result =
          try {
            PublishedUiBuilderCatalog.compose(document, inventory, exports)
          } catch (failure: Throwable) {
            fail("$label, $arm: threw ${failure::class.simpleName}: ${failure.message}")
          }
        // Composing is allowed — several of these are merely unusual, not malformed. What is not
        // allowed is escaping the two branches the caller knows how to handle.
        assertTrue(
          result is PublishedUiBuilderCatalog.Result.Composed ||
            result is PublishedUiBuilderCatalog.Result.Unusable,
          "$label, $arm returned neither Composed nor Unusable",
        )
      }
    }
  }

  /**
   * The record-to-record collision branch, exercised: `Widget` derives `h/widget`, the policy hands
   * the same id to `Nameless`, so `Nameless` hits `taken.containsKey`. The assertion (one
   * component, not two) guards against a fixture that silently stops colliding.
   */
  /**
   * A builtin's properties are validated like a component's. Uses this file's own record so the
   * test cannot depend on another catalog's fixture shape, and the wire key `properties` (per
   * `ui-builder.policy.schema.json`), so renaming the field back fails this test.
   */
  @Test
  fun `a builtin's malformed jsonType is refused`() {
    val document =
      """
      {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
       "record":{"file":"components.json","schemaVersion":1,"components":2},
       "statusSemantics":{"componentIdPrefix":"h/",
         "builtins":{"layout/box":{"role":"Container",
           "properties":[{"name":"pad","jsonType":{}}]}}}}
      """
        .trimIndent()

    val result = PublishedUiBuilderCatalog.compose(document, record, exports)

    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Unusable,
      "a builtin with a jsonType of {} composed instead of being refused",
    )
    assertTrue(
      (result as PublishedUiBuilderCatalog.Result.Unusable).reason.contains("layout/box.pad"),
      "the refusal does not name the builtin property: ${result.reason}",
    )
  }

  /**
   * `max` below `min` is a slot no child count satisfies, so it is refused. `validateCatalog` only
   * sees `m3-catalog`, so published catalogs rely on this check.
   */
  @Test
  fun `a slot cardinality no child count satisfies is refused`() {
    val document =
      """
      {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
       "record":{"file":"components.json","schemaVersion":1,"components":2},
       "statusSemantics":{"componentIdPrefix":"h/",
         "components":{"h/widget":{"record":":hostile/com.example.AKt.Widget",
           "slotCapabilities":[{"name":"content","cardinality":{"min":1,"max":0}}]}}}}
      """
        .trimIndent()

    val result = PublishedUiBuilderCatalog.compose(document, record, exports)

    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Unusable,
      "max below min composed into a component nobody can author",
    )
    assertTrue(
      (result as PublishedUiBuilderCatalog.Result.Unusable).reason.contains("h/widget.content"),
      "the refusal does not name the cardinality: ${result.reason}",
    )
  }

  /**
   * The same rule over a builtin's derived pair: with `max` writable, `required: true` plus `max:
   * 0` yields an impossible `min = 1, max = 0`.
   */
  @Test
  fun `a builtin slot bound no child count satisfies is refused`() {
    val document =
      """
      {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
       "record":{"file":"components.json","schemaVersion":1,"components":2},
       "statusSemantics":{"componentIdPrefix":"h/",
         "builtins":{"h/host":{"role":"screen-root",
           "slots":{"content":{"required":true,"max":0}}}}}}
      """
        .trimIndent()

    val result = PublishedUiBuilderCatalog.compose(document, record, exports)

    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Unusable,
      "a builtin bound below its own minimum composed into a host nobody can author",
    )
    assertTrue(
      (result as PublishedUiBuilderCatalog.Result.Unusable).reason.contains("h/host.content"),
      "the refusal does not name the slot: ${result.reason}",
    )
  }

  @Test
  fun `the collision fixture actually collides`() {
    val result = PublishedUiBuilderCatalog.compose(COLLIDING, record, exports)
    val composed =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        result,
        "the colliding document should still compose — a collision is skipped, not fatal",
      )
    assertEquals(
      listOf("h/widget"),
      composed.catalog.components.map { it.componentId },
      "only the first claimant of h/widget may be taken",
    )
  }

  @Test
  fun `a components field that is not a map is refused, not read as empty`() {
    // The sweep only proves these return; the equivalence script relies on the reader refusing
    // them, so pin that stronger claim.
    listOf("components is an array", "components is null").forEach { label ->
      val document = hostile.first { it.first == label }.second
      val result = PublishedUiBuilderCatalog.compose(document, record, exports)
      assertTrue(
        result is PublishedUiBuilderCatalog.Result.Unusable,
        "$label should be refused, not read as a policy with no components",
      )
    }
  }

  private companion object {
    /** See [the collision fixture actually collides]. */
    val COLLIDING =
      """
      {"schema":"compose-ui-builder-catalog/v1","catalog":{"id":"h"},
       "record":{"file":"components.json","schemaVersion":1,"components":2},
       "statusSemantics":{"componentIdPrefix":"h/",
         "components":{"h/widget":{"record":":hostile/com.example.AKt.Nameless"}}}}
      """
        .trimIndent()

    /** A `frame` object nested [depth] deep — the shape a recursive-descent parser dies on. */
    fun nest(depth: Int): String = buildString {
      repeat(depth) { append("""{"a":""") }
      append("1")
      repeat(depth) { append("}") }
    }
  }
}
