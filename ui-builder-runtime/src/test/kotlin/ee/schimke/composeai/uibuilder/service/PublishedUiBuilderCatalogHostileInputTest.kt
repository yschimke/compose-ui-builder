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
 * A published `ui-builder.json` comes from **another repository**, over the network, and is read at
 * startup. Nothing in this process reviews it first.
 *
 * [PublishedUiBuilderCatalogTest] pins the refusals this reader was written to make — a schema
 * major it does not know, a missing prefix, a policy that expects an inventory it does not have.
 * This pins the weaker and more important property: that **no** document reaches the end of
 * `compose` by throwing. The refusal list is what the author thought of; a hostile document is what
 * nobody did.
 *
 * Why it matters more than a returned `Unusable`: the call site in `ServeRunner` handles both
 * `Result` branches, and an exception is neither. It escapes into `openUiBuilderLane`, whose caller
 * turns any failure into `uiBuilderDisabledWarning` and a null lane — so one catalog's malformed
 * file would take the UI builder away from **every** catalog on the host, which is exactly the
 * blast radius the per-catalog fallback exists to prevent. The server itself survives either way;
 * this is about the builder surviving with it.
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
   * Each case is a document a generator would never write and a mistake — or an attacker —
   * plausibly could. None is expected to compose; every one is expected to *return*.
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
      // The two collections this reader iterates. A policy naming a record id that is not in the
      // record, and a builtin colliding with a component, are the two shapes that decide what
      // `taken` and `skipped` end up holding.
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
      // The reader decodes `components` as a Map; anything else fails its decode. Pinned because
      // `.github/scripts/ui-builder-equivalence.sh` now refuses these for the same reason, and the
      // two refusals have to agree about which documents are refusable.
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
   * The record-to-record collision branch, exercised rather than merely named.
   *
   * `Widget` carries `componentIds: ["Widgets/Widget"]`, so with no policy it derives `h/widget`.
   * The policy below hands that **same** id to `Nameless`, and the record is walked in order, so
   * `Widget` takes `h/widget` first and `Nameless` hits `taken.containsKey(componentId)`.
   *
   * A first draft of this case used the keys `"h/same"` and `"h/same "` and proved nothing: the
   * component ids are the map keys, `compose` does not trim them, so the two are distinct and both
   * components were admitted. It read like a collision test and exercised no collision — the same
   * failure mode this whole file exists to catch, one level up. The assertion below is what stops
   * it recurring: a fixture that stopped colliding would take two components, not one.
   */
  /**
   * A builtin's properties reach `builtinCapability` by the same route a component's reach
   * `capability`, and are read by the same validator — checking only `components` was the container
   * half of that fix.
   *
   * Written against this file's own two-component record rather than m3-catalog's published
   * fixture, which is where it started. That fixture states no builtins at all (m3-catalog's policy
   * says the catalog owns none), so the test inserted one by string-splicing a `builtins` block
   * ahead of the first `"components": {` it found. It passed, and it was one regeneration of that
   * fixture away from splicing into nothing and passing anyway. A refusal test that depends on the
   * shape of an unrelated catalog is checking that catalog, not the validator.
   *
   * The second half of the same lesson, found the next time this file was read: the key here was
   * `propertyCapabilities`, which is what the SERVER called the field and not what a catalog can
   * write. `ui-builder.policy.schema.json` spells a builtin's list `properties` with
   * `additionalProperties: false`, so the only documents this validator had ever seen were the ones
   * this test wrote for it. Every real builtin composed with zero properties and nothing said so.
   * The key below is now the wire name, which makes this a mutation check on it: rename the field
   * back and this test fails.
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
   * `max` below `min` is a slot no child count satisfies, so the component cannot be authored at
   * all — the same "impossible to author" the malformed `jsonType` above is refused for.
   *
   * `validateCatalog` requires `catalogSystemId == "m3-catalog"`, so it only ever sees the packaged
   * catalog; a published one reaches the shelf unvalidated unless this refuses it.
   *
   * Moved here for the reason above, and for one more: its previous form spliced `"max": 0,` into
   * the first `"cardinality": {` in m3-catalog's fixture, so which slot of which component it
   * corrupted was whatever the serialiser happened to order first.
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
   * The same rule over the pair a **builtin** derives, which nothing applied until a builtin could
   * state `max`.
   *
   * The builtins loop checked only properties, and that was sound while `required` set the minimum
   * and the maximum was always unbounded: no pair it could produce was impossible. A writable bound
   * makes `required: true` with `max: 0` reachable, and it composes to the `min = 1, max = 0` the
   * test above refuses for a component — a slot no child count satisfies, on a catalog that loaded
   * cleanly.
   *
   * Raised by the review bot on compose-preview-server#747 against the change that added the field,
   * and true: it is that change's own gap rather than a pre-existing one.
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
    // The sweep above only proves these RETURN. The claim the equivalence gate now makes about them
    // is stronger — that the reader REFUSES them — and `.github/scripts/ui-builder-equivalence.sh`
    // refuses the same two shapes on the strength of it. Asserting "does not throw" and calling
    // that agreement would be a check that does not check, so the stronger claim is pinned here.
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
