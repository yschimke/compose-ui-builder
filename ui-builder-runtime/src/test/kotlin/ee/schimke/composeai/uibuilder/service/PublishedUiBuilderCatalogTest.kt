package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityStatusV1
import ee.schimke.composeai.uibuilder.protocol.SvgFallbackV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The proof the catalog contract exists for: a catalog **this binary has never heard of** becomes a
 * servable capability catalog from its own published files.
 *
 * `test-catalog` is deliberately not one of the three ids in `ProductionUiBuilderRuntime` — there
 * is no `testCatalog()` synthesiser, no enum value, no `when` branch. If this passes, the only
 * thing the server knows about it came out of the two files below, which is exactly what
 * `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` sets as the test of the whole design.
 *
 * Kept trivial on purpose. The real catalogs are proved equivalent to what the server synthesises
 * by `.github/scripts/ui-builder-equivalence.sh` against the frozen goldens; this is about the
 * *route* being open, not about any particular catalog's contents.
 */
class PublishedUiBuilderCatalogTest {

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
        "module": ":test-catalog",
        "variant": "debug",
        "components": [
          {
            "canonicalId": ":test-catalog/com.example.TestKt.Widget",
            "componentIds": ["Widgets/Widget"],
            "symbol": {
              "name": "Widget",
              "callable": "com.example.Widget",
              "jvmOwner": "com.example.TestKt",
              "origin": "PROJECT"
            },
            "parameters": [
              { "name": "label", "type": "String", "typeFqn": "kotlin.String", "hasDefault": false },
              { "name": "count", "type": "Int", "typeFqn": "kotlin.Int", "hasDefault": true }
            ],
            "slots": [],
            "code": { "imports": ["com.example.Widget"] }
          },
          {
            "canonicalId": ":test-catalog/com.example.TestKt.Unlabelled",
            "componentIds": [],
            "symbol": {
              "name": "RTLText",
              "callable": "com.example.RTLText",
              "jvmOwner": "com.example.TestKt",
              "origin": "PROJECT"
            },
            "parameters": [],
            "slots": [],
            "code": { "imports": ["com.example.RTLText"] }
          }
        ]
      }
      """
        .trimIndent()
    )

  private fun published(
    prefix: String = "test-catalog/",
    schema: String = "compose-ui-builder-catalog/v1",
    extra: String = "",
  ) =
    """
    {
      "schema": "$schema",
      "catalog": { "id": "test-catalog", "title": "Test catalog" },
      "record": { "file": "components.json", "schemaVersion": 1, "components": 2 },
      "statusSemantics": {
        "platform": "test",
        "platformLabel": "Test",
        "componentIdPrefix": "$prefix",
        "browserPreview": { "renderer": "remote-compose-document", "format": "rc" },
        "frame": { "adapter": "frame/rect" },
        "componentMenu": { "groupOrder": ["Widgets"] },
        "builtins": {
          "test-catalog/screen": { "role": "screen-root", "slots": { "content": {} } }
        },
        "components": {
          "test-catalog/widget": {
            "record": ":test-catalog/com.example.TestKt.Widget",
            "displayName": "The Widget",
            "canvas": "box",
            "canvasMapping": {
              "properties": { "targetLabel": "label" },
              "slots": { "content": "children" },
              "defaults": { "variant": { "type": "enum", "value": "filled" } }
            },
            "traits": ["scrollable"]
          }
        }$extra
      }
    }
    """
      .trimIndent()

  @Test
  fun `a catalog the server has never heard of composes from its published files`() {
    val result = PublishedUiBuilderCatalog.compose(published(), record, exports)
    val composed =
      assertTrue(result is PublishedUiBuilderCatalog.Result.Composed).let {
        result as PublishedUiBuilderCatalog.Result.Composed
      }
    val catalog = composed.catalog

    assertEquals("test-catalog", catalog.benchmark.catalogSystemId)
    // Three: the annotated component under its declared id, the unannotated one under a DERIVED id,
    // and the builtin — which has no call site and so exists only because the policy says so.
    assertEquals(
      listOf("test-catalog/widget", "test-catalog/rtl-text", "test-catalog/screen"),
      catalog.components.map { it.componentId },
    )

    val widget = assertNotNull(catalog.components.first { it.componentId == "test-catalog/widget" })
    assertEquals("The Widget", widget.displayName)
    assertEquals(listOf("scrollable"), widget.traits)
    // `label` has no default and is not nullable; `count` defaults.
    assertEquals(
      listOf("label" to true, "count" to false),
      widget.properties.map { it.name to it.required },
    )
    assertEquals(WasmAdapterStatusV1.SUPPORTED, widget.wasm?.adapterStatus)
    assertEquals("box", widget.wasm?.canvas)
    assertEquals("label", widget.wasm?.canvasMapping?.properties?.get("targetLabel"))
    assertEquals("children", widget.wasm?.canvasMapping?.slots?.get("content"))
    assertEquals(
      "filled",
      widget.wasm
        ?.canvasMapping
        ?.defaults
        ?.get("variant")
        ?.jsonObject
        ?.get("value")
        ?.jsonPrimitive
        ?.content,
    )
    assertEquals("remote-compose-document", catalog.browserPreview?.renderer)
    assertEquals(ExportFormatV1.RC, catalog.browserPreview?.format)

    // The builtin is a container because the policy gave it a slot, and carries no code capability:
    // there is no call site to compile, which is what makes it a builtin.
    val screen = assertNotNull(catalog.components.first { it.componentId == "test-catalog/screen" })
    assertEquals(listOf("content"), screen.slots.map { it.name })
    assertNull(screen.code)

    // Every reader downstream looks in `statusSemantics`, so the published block is passed through
    // whole — including the fields this composer does not itself interpret.
    assertTrue("frame" in catalog.statusSemantics.keys)
    assertTrue("componentMenu" in catalog.statusSemantics.keys)
    assertTrue(composed.note.startsWith("test-catalog — published ui-builder.json"))
  }

  @Test
  fun `a record component states its shelf role, and an unknown word keeps the derivation`() {
    fun widgetRole(shelfRole: String?): String {
      val json =
        published().let {
          if (shelfRole == null) it
          else
            it.replace(
              "\"traits\": [\"scrollable\"]",
              "\"traits\": [\"scrollable\"], \"shelfRole\": \"$shelfRole\"",
            )
        }
      val result = PublishedUiBuilderCatalog.compose(json, record, exports)
      val catalog =
        assertTrue(result is PublishedUiBuilderCatalog.Result.Composed).let {
          (result as PublishedUiBuilderCatalog.Result.Composed).catalog
        }
      return catalog.components.first { it.componentId == "test-catalog/widget" }.role
    }

    // No slots, so the derivation says Leaf; slot presence cannot say Scaffold at all.
    assertEquals("Leaf", widgetRole(null))
    assertEquals("Scaffold", widgetRole("Scaffold"))
    assertEquals("Container", widgetRole("Container"))
    // A word that names no shelf is ignored rather than served, exactly as for a builtin.
    assertEquals("Leaf", widgetRole("Pane"))
  }

  @Test
  fun `a published catalog carries its Compose source adapter onto its capability`() {
    val result =
      PublishedUiBuilderCatalog.compose(
        published(
          extra =
            ", \"composeSourceExport\": { \"adapter\": \"compose-material3\", \"version\": 1 }"
        ),
        record,
        exports,
      )
    val catalog =
      assertTrue(result is PublishedUiBuilderCatalog.Result.Composed).let {
        (result as PublishedUiBuilderCatalog.Result.Composed).catalog
      }

    assertEquals("compose-material3", catalog.composeSourceExport?.adapter)
    assertEquals(1, catalog.composeSourceExport?.version)
  }

  @Test
  fun `authored exceptions augment rather than replace record-derived properties`() {
    val document =
      published(
        extra =
          """,
        "components": {
          "test-catalog/widget": {
            "record": ":test-catalog/com.example.TestKt.Widget",
            "propertyCapabilities": [
              { "name": "stableKey", "jsonType": "string" },
              { "name": "label", "jsonType": "string", "required": false,
                "notes": "policy overrides the convention" }
            ]
          }
        }"""
      )

    val result = PublishedUiBuilderCatalog.compose(document, record, exports)
    val catalog = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result).catalog
    val properties =
      catalog.components.single { it.componentId == "test-catalog/widget" }.properties

    assertEquals(listOf("stableKey", "label", "count"), properties.map { it.name })
    assertTrue(properties.single { it.name == "label" }.notes!!.contains("overrides"))
  }

  @Test
  fun `an unannotated component gets the derived id a saved design would store`() {
    // `RTLText` -> `rtl-text`: a run of capitals is one word. This is the one derivation the reader
    // and the generator have to agree on without a field to agree through, so it is pinned here as
    // well as by the equivalence gate.
    assertEquals("rtl-text", PublishedUiBuilderCatalog.slug("RTLText"))
    assertEquals("checkbox-button", PublishedUiBuilderCatalog.slug("CheckboxButton"))
    assertEquals("button2", PublishedUiBuilderCatalog.slug("Button2"))
    assertEquals("top-app-bar", PublishedUiBuilderCatalog.slug("TopAppBar"))
    // The two cases that separate this from a naive port, both reported by Codex against the
    // JavaScript one in `.github/scripts/ui-builder-equivalence.sh`, which pins the same table.
    //
    // `lowercaseChar()` is a SINGLE-character mapping: U+0130 lowercases to `i`, where a
    // JavaScript `toLowerCase()` yields `i` plus a combining dot.
    assertEquals("i-button", PublishedUiBuilderCatalog.slug("\u0130Button"))
    // And this loop walks `Char`s, so a supplementary code point is two surrogates, neither of
    // which is a letter — both separate. A port iterating code points keeps it and produces an id
    // with half a surrogate pair in it.
    assertEquals("a-b", PublishedUiBuilderCatalog.slug("A\uD801\uDC00B"))
    // `isLetterOrDigit()` is `isLetter() || isDigit()`, and `isDigit()` is the DECIMAL category
    // alone — a superscript two is numeric but not a digit, so it separates.
    assertEquals("widget-x", PublishedUiBuilderCatalog.slug("Widget\u00B2X"))
    // And the word-boundary test asks `isDigit()` too, so a decimal digit outside ASCII starts a
    // word after it — the same predicate as the admission test, which a port can easily split.
    assertEquals("a\u0662-b", PublishedUiBuilderCatalog.slug("A\u0662B"))
    // `isLowerCase()` is a case PROPERTY, not "differs from its uppercase form": U+02B0 is
    // lowercase to the JVM and has no distinct case conversion, so a round-trip heuristic — which
    // is what a port reaches for — calls it neither upper nor lower and misses the boundary.
    assertEquals("\u02B0-a", PublishedUiBuilderCatalog.slug("\u02B0A"))
  }

  @Test
  fun `a catalog that cannot be composed says why instead of throwing`() {
    // Each of these leaves the catalog to whatever the server can synthesise; none is fatal. A host
    // that died over another repository's bad publish would be down until that repo's CI ran again.
    val futureMajor =
      PublishedUiBuilderCatalog.compose(
        published(schema = "compose-ui-builder-catalog/v2"),
        record,
        exports,
      )
    assertTrue(futureMajor is PublishedUiBuilderCatalog.Result.Unusable)
    assertTrue("v2" in (futureMajor as PublishedUiBuilderCatalog.Result.Unusable).reason)

    val noPrefix = PublishedUiBuilderCatalog.compose(published(prefix = ""), record, exports)
    assertTrue(noPrefix is PublishedUiBuilderCatalog.Result.Unusable)

    val notJson = PublishedUiBuilderCatalog.compose("[]", record, exports)
    assertTrue(notJson is PublishedUiBuilderCatalog.Result.Unusable)
  }

  @Test
  fun `a file that expects a record is refused when none arrived`() {
    // The dangerous case, and the reason it is a refusal rather than a shrug: composing a policy
    // that expects an inventory against no inventory SUCCEEDS, with the builtins alone — so a cold
    // host would replace a full shelf with a nearly empty one and say nothing. The file states the
    // count it was generated against, which is what makes the mistake detectable at all.
    val result = PublishedUiBuilderCatalog.compose(published(), record = null, exports)
    assertTrue(result is PublishedUiBuilderCatalog.Result.Unusable)
    assertTrue(
      "builtins alone" in (result as PublishedUiBuilderCatalog.Result.Unusable).reason,
      result.reason,
    )
  }

  /**
   * Everything a builtin declares reaches the shelf, which nothing checked.
   *
   * A builtin is the only way a catalog can offer a component the record cannot carry — a host
   * frame, a shape, an image asset with no call site — so what it declares is all there is. Three
   * fields were being read past:
   * - `properties` was decoded under the server's own name `propertyCapabilities`, which
   *   `ui-builder.policy.schema.json` forbids (`additionalProperties: false`), so every builtin a
   *   real catalog could publish composed with **zero** properties;
   * - `slots` was `Map<String, JsonElement>`, so `required` became `min = 0` and `acceptedTraits`
   *   vanished — the rules that stop a design putting a scaffold inside a widget's background slot
   *   with it;
   * - `traits` was hardcoded to the empty list, so no other component's slot could accept one.
   *
   * `max` joined them later, for the same reason and from the same case: a `remote-m3` widget
   * container hosts exactly one child and a builtin had no way to write the bound.
   *
   * Each is asserted here against the wire names, so the fixture is a document a catalog could
   * actually publish rather than one written to match the reader.
   */
  @Test
  fun `a builtin's declared vocabulary reaches the shelf`() {
    val document =
      published(
        extra =
          """,
        "builtins": {
          "test-catalog/host": {
            "role": "screen-root",
            "displayName": "The Host",
            "traits": ["WidgetHost"],
            "slots": {
              "content": {
                "required": true,
                "max": 1,
                "acceptedRoles": ["Container", "Leaf"],
                "acceptedTraits": ["AnyContent"],
                "role": "overlay"
              },
              "background": { "acceptedTraits": ["DrawLayer"] }
            },
            "properties": [
              { "name": "cornerRadiusDp", "jsonType": "number" },
              { "name": "mode", "jsonType": "string", "required": true,
                "allowedValues": ["squircle", "round"] }
            ],
            "modifierCapabilities": ["padding"]
          }
        }"""
      )
    val result = PublishedUiBuilderCatalog.compose(document, record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the builtin did not compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    val host =
      (result as PublishedUiBuilderCatalog.Result.Composed).catalog.components.single {
        it.componentId == "test-catalog/host"
      }

    assertEquals("The Host", host.displayName)
    // `screen-root` is the structural role, and the shelf role that follows from it is Scaffold —
    // not the Container that "it has slots" would derive. A design root arriving as an ordinary
    // container is what the editor then calls it.
    assertEquals("Scaffold", host.role)
    assertEquals(listOf("WidgetHost"), host.traits)
    assertEquals(listOf("padding"), host.modifierCapabilities)
    assertEquals(
      listOf("cornerRadiusDp", "mode"),
      host.properties.map { it.name }.sorted(),
      "a builtin's properties are its only source, and they were being dropped",
    )
    assertEquals(true, host.properties.single { it.name == "mode" }.required)
    assertEquals(
      listOf("squircle", "round"),
      host.properties.single { it.name == "mode" }.allowedValues.map { it.toString().trim('"') },
    )
    val content = host.slots.single { it.name == "content" }
    assertEquals(
      1,
      content.cardinality.min,
      "a required slot that accepts zero children is not required",
    )
    // The other end, and the last thing a frozen widget container needed a builtin to say. Without
    // it `remote-m3`'s two `WidgetContainer` components composed with an unbounded `content` slot,
    // so the shelf offered a widget host a design could put three children into.
    assertEquals(
      1,
      content.cardinality.max,
      "a slot declaring `max` composed unbounded, so the shelf admitted children the host cannot draw",
    )
    assertEquals(listOf("AnyContent"), content.acceptedTraits)
    assertEquals(listOf("Container", "Leaf"), content.acceptedRoles)
    assertEquals(
      listOf("DrawLayer"),
      host.slots.single { it.name == "background" }.acceptedTraits,
    )
    assertEquals(0, host.slots.single { it.name == "background" }.cardinality.min)
    // And a slot that says nothing is still unbounded: `max` is an opt-in bound, not a default of
    // one that every existing builtin would silently acquire.
    assertEquals(null, host.slots.single { it.name == "background" }.cardinality.max)
  }

  @Test
  fun `a builtin states its shelf role, its lanes, its call and its slot order`() {
    // Five things this composed for a builtin by DERIVING them, which is not the same as a catalog
    // being silent: the derived answer was served as though the catalog had agreed with it. Each
    // assertion below is a value the derivation gets wrong for the donor catalog that publishes
    // the builder's own vocabulary (`compose-foundation`, yschimke/m3-catalog).
    val document =
      published(
        extra =
          """,
        "builtins": {
          "test-catalog/box": {
            "role": "container",
            "shelfRole": "Container",
            "canvas": "layout/box",
            "wasm": { "adapterStatus": "planned" },
            "code": { "symbol": "Box", "imports": ["androidx.compose.foundation.layout.Box"] },
            "svg": { "status": "verified", "fallback": "none" },
            "slots": { "children": { "ordered": false } }
          },
          "test-catalog/stack": {
            "role": "container",
            "shelfRole": "Scaffold",
            "slots": { "children": {} }
          }
        }"""
      )
    val result = PublishedUiBuilderCatalog.compose(document, record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the builtin did not compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    val components = (result as PublishedUiBuilderCatalog.Result.Composed).catalog.components
    val box = components.single { it.componentId == "test-catalog/box" }

    // `container` is a structural role the template engine grew for exactly this; the shelf role
    // beside it is the one served as `role`, and it is stated rather than derived.
    assertEquals("Container", box.role)
    // The derivation can produce SUPPORTED and UNSUPPORTED and never PLANNED, which is the
    // difference between an adapter that is coming and one that will never exist.
    assertEquals(WasmAdapterStatusV1.PLANNED, box.wasm.adapterStatus)
    // The adapter id itself is executable catalog data, not only the source of status prose. A
    // direct rendering host needs this exact word to dispatch the component without knowing its id.
    assertEquals("layout/box", box.wasm.canvas)
    // ...and only the field the catalog stated moves. Platform support and the note still come
    // from the adapter id, so stating one field is not a claim about the other two.
    assertEquals("true", box.wasm.platformSupported.toString())
    assertEquals("Drawn on the canvas by the `layout/box` adapter.", box.wasm.notes)
    // A builtin has no record entry, so before it could state this it published no code capability
    // whatsoever — and nothing in a foundation catalog's record can name `Box`, because discovery
    // scopes library components to material3/material/wear.
    assertEquals("Box", assertNotNull(box.code).symbol)
    assertEquals(listOf("androidx.compose.foundation.layout.Box"), assertNotNull(box.code).imports)
    assertEquals(SvgCapabilityStatusV1.VERIFIED, assertNotNull(box.svg).status)
    assertEquals(SvgFallbackV1.NONE, assertNotNull(box.svg).fallback)
    // Every builtin slot composed as ordered because there was nothing to read, and the packaged
    // vocabulary this donor republishes has six of its fifteen slots unordered.
    assertEquals(false, box.slots.single { it.name == "children" }.ordered)

    // A stated shelf role beats the derivation even where the derivation has an answer: `stack`
    // has slots, so "it has slots" would make it a Container, and it says it is a design root.
    val stack = components.single { it.componentId == "test-catalog/stack" }
    assertEquals("Scaffold", stack.role)
    // An absent `ordered` keeps the old default, so no declaration published before the field
    // changes meaning by being reread.
    assertEquals(true, stack.slots.single { it.name == "children" }.ordered)
  }

  @Test
  fun `the editing canvas's mock reaches the wire for a component and a builtin`() {
    // A container drawn as itself cannot show a child past the frame's edge, so a catalog states
    // how the canvas lays its children out while an author is inside it. The policy states it
    // beside `canvas` for both kinds of component; the wire nests it under `wasm`, because that is
    // the canvas-lane block there — and this composition is what carries it across.
    val document =
      published(
        extra =
          """,
        "components": {
          "test-catalog/widget": {
            "record": ":test-catalog/com.example.TestKt.Widget",
            "displayName": "The Widget",
            "canvas": "box",
            "unrolled": { "layout": "stack" }
          }
        },
        "builtins": {
          "test-catalog/screen": { "role": "screen-root", "slots": { "content": {} } },
          "test-catalog/lazy-list": {
            "role": "list",
            "canvas": "layout/lazy-column",
            "unrolled": { "layout": "wrap", "cellWidthDp": 190, "spacingDp": 4 }
          },
          "test-catalog/tabs": {
            "role": "list",
            "canvas": "layout/scrollable-tab-row",
            "unrolled": { "layout": "carousel-of-cards" }
          }
        }"""
      )
    val result = PublishedUiBuilderCatalog.compose(document, record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the mock did not compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    val components = (result as PublishedUiBuilderCatalog.Result.Composed).catalog.components

    val widget = components.single { it.componentId == "test-catalog/widget" }
    assertEquals("stack", assertNotNull(widget.wasm.unrolled).layout)

    val lazyList = components.single { it.componentId == "test-catalog/lazy-list" }
    assertEquals("wrap", assertNotNull(lazyList.wasm.unrolled).layout)
    assertEquals("190", assertNotNull(lazyList.wasm.unrolled).cellWidthDp.toString())
    assertEquals("4", assertNotNull(lazyList.wasm.unrolled).spacingDp.toString())

    // The layout word is the BUILDER's vocabulary, exactly as `canvas` is, so the server carries a
    // name it has never heard of rather than refusing it — the builder resolves it against its own
    // registry and an unknown name is inert.
    val tabs = components.single { it.componentId == "test-catalog/tabs" }
    assertEquals("carousel-of-cards", assertNotNull(tabs.wasm.unrolled).layout)

    // Absence is the default and stays absence: a component that states no mock keeps its own
    // layout while editing, which is what every component did before the field existed.
    val screen = components.single { it.componentId == "test-catalog/screen" }
    assertNull(screen.wasm.unrolled)
  }

  @Test
  fun `a word this build cannot decode costs its field, never the catalog`() {
    // `adapterStatus`, `svg.status` and `svg.fallback` are closed enums on the wire, and a
    // capability document carrying a word the builder cannot decode fails the WHOLE document
    // rather than one field. A catalog is published once and read by builders of several vintages
    // that its publisher cannot upgrade, so a typo — or a word from a later minor — must cost that
    // field and leave the palette standing.
    val document =
      published(
        extra =
          """,
        "builtins": {
          "test-catalog/box": {
            "role": "container",
            "shelfRole": "container",
            "canvas": "layout/box",
            "wasm": { "adapterStatus": "soon", "notes": "Coming." },
            "svg": { "status": "verified", "fallback": "tracing-paper" },
            "slots": { "children": {} }
          }
        }"""
      )
    val result = PublishedUiBuilderCatalog.compose(document, record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "one undecodable word took the whole catalog off the shelf",
    )
    val box =
      (result as PublishedUiBuilderCatalog.Result.Composed).catalog.components.single {
        it.componentId == "test-catalog/box"
      }

    // `container` is the STRUCTURAL role, not a shelf role, so the stated shelf role is dropped
    // and the derivation answers instead — the answer every catalog got before the field existed.
    assertEquals("Container", box.role)
    assertEquals(WasmAdapterStatusV1.SUPPORTED, box.wasm.adapterStatus)
    // The field beside the bad one survives, because the override is field by field.
    assertEquals("Coming.", box.wasm.notes)
    // An undecodable half drops the block, which is what a catalog stating no block gets.
    assertNull(box.svg)
  }

  @Test
  fun `a builtin may ship its wrapper implementation without making the server know its id`() {
    val document =
      published(
        extra =
          """,
        "builtins": {
          "test-catalog/novel-host": {
            "role": "screen-root",
            "displayName": "Novel Host",
            "canvas": "placeholder",
            "slots": { "content": { "required": true } },
            "implementation": ":test-catalog/com.example.TestKt.Unlabelled"
          }
        }"""
      )

    val result =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(document, record, exports)
      )
    val host = result.catalog.components.single { it.componentId == "test-catalog/novel-host" }

    assertEquals("com.example.RTLText", host.code?.symbol)
    assertEquals("com.example.RTLText", result.records.getValue(host.componentId).symbol.callable)
    assertEquals(WasmAdapterStatusV1.UNSUPPORTED, host.wasm?.adapterStatus)
  }

  @Test
  fun `a catalog of builtins alone needs no component record`() {
    // Policy without inventory is the simplest thing the contract can express, and refusing it here
    // would be refusing a catalog whose components all lack call sites.
    val result =
      PublishedUiBuilderCatalog.compose(
        published().replace("\"components\": 2", "\"components\": 0"),
        record = null,
        exports,
      )
    val composed =
      assertTrue(result is PublishedUiBuilderCatalog.Result.Composed).let {
        result as PublishedUiBuilderCatalog.Result.Composed
      }
    assertEquals(listOf("test-catalog/screen"), composed.catalog.components.map { it.componentId })
  }
}
