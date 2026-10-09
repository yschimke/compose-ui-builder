package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [CatalogCutoverShadow]: the check a deployment runs for a catalog it shadows before flipping it.
 * Its owned half is the readiness probe itself (`CatalogCutoverProbe` delegates to it, so the gap
 * ledger in [CatalogCutoverReadinessTest] is its golden); this pins the catalog comparison and the
 * executor's on-demand synthesis it depends on.
 */
class CatalogCutoverShadowTest {

  private val executor =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .also { it.catalogOwnership = CatalogOwnership.NONE }
      .build()

  @Test
  fun `a catalog compared with itself has no differences`() {
    val wear = assertNotNull(executor.synthesisedCatalog("wear-m3"))
    assertEquals(emptyList(), CatalogCutoverShadow.catalogDifferences(wear, wear))
  }

  @Test
  fun `the executor synthesises on demand, and nothing for an id it does not know`() {
    assertNotNull(executor.synthesisedCatalog("remote-m3"))
    assertNull(executor.synthesisedCatalog("remote-widgets"))
  }

  @Test
  fun `a component or property only one side has is reported, and by which side`() {
    val kotlin = assertNotNull(executor.synthesisedCatalog("wear-m3"))
    val dropped = kotlin.components.first { it.properties.isNotEmpty() }
    // A builder id (`asset/image`) is reported under the catalog's prefix, its own ids as they are.
    val removed = kotlin.components.last()
    val removedLine =
      if (removed.componentId.startsWith("wear-m3/")) removed.componentId
      else "wear-m3/${removed.componentId}"
    val property = dropped.properties.first().name
    val published =
      kotlin
        .newBuilder()
        .also { builder ->
          builder.components =
            kotlin.components.drop(0).map { component ->
              if (component.componentId != dropped.componentId) component
              else
                component
                  .newBuilder()
                  .also {
                    it.properties = component.properties.filterNot { p -> p.name == property }
                  }
                  .build()
            } - removed
        }
        .build()

    val differences = CatalogCutoverShadow.catalogDifferences(kotlin, published)
    assertContains(
      differences,
      "$removedLine: only the Kotlin catalog has it",
    )
    assertContains(differences, "${dropped.componentId}: properties: loses $property")
  }

  /**
   * The comparison a box would print for Wear today, measured against the captured delivery branch:
   * not asserted line by line (the catalogs move independently), but it must name components rather
   * than fail, and every line must be addressed to the catalog.
   */
  @Test
  fun `the live Wear catalog compares against the Kotlin one it would replace`() {
    val differences =
      CatalogCutoverShadow.catalogDifferences(
        assertNotNull(executor.synthesisedCatalog("wear-m3")),
        CatalogCutoverFixtures.catalog("wear-m3"),
      )
    assertTrue(differences.all { it.startsWith("wear-m3/") }, differences.joinToString("\n"))
    assertTrue(
      differences.none { it.startsWith("wear-m3/wear-m3/") },
      differences.joinToString("\n"),
    )
  }

  /**
   * The report compares served catalogs, so the builder vocabulary both sides are given is never
   * reported as lost, and its findings are the readiness ledger's lines for the catalog.
   */
  @Test
  fun `the report compares what an editor is served, and agrees with the ledger`() {
    val report =
      CatalogCutoverShadow.report(
        catalogId = "wear-m3",
        published = CatalogCutoverFixtures.catalog("wear-m3"),
        templates = CatalogCutoverFixtures.templates("wear-m3"),
        fixture = CatalogCutoverProbe.fixture,
        packComponents = CatalogCutoverFixtures.composed("wear-m3").records,
        exportRecord = CatalogCutoverFixtures.exportRecord("wear-m3"),
        nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId("wear-m3"),
      )
    val differences = assertNotNull(report.differences)
    assertTrue(
      differences.none { it.startsWith("wear-m3/layout/") },
      differences.joinToString("\n"),
    )
    assertEquals(
      CatalogCutoverProbe.findings(
        published = mapOf("wear-m3" to CatalogCutoverFixtures.catalog("wear-m3"))
      ),
      report.findings,
    )
  }

  @Test
  fun `a catalog the build synthesises nothing for has nothing to lose`() {
    val report =
      CatalogCutoverShadow.report(
        catalogId = "glimmer-catalog",
        published = CatalogCutoverFixtures.catalog("glimmer-catalog"),
        templates = CatalogCutoverFixtures.templates("glimmer-catalog"),
        fixture = CatalogCutoverProbe.fixture,
      )
    assertNull(report.differences)
  }

  /** Owning a catalog with no platform is refused, so shadow reports that rather than throwing. */
  @Test
  fun `a published catalog that declares no platform is a finding, not a crash`() {
    val wear = CatalogCutoverFixtures.catalog("wear-m3")
    val unplatformed =
      wear
        .newBuilder()
        .also {
          it.statusSemantics =
            JsonObject(wear.statusSemantics - CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY)
        }
        .build()
    val report =
      CatalogCutoverShadow.report(
        catalogId = "wear-m3",
        published = unplatformed,
        templates = CatalogCutoverFixtures.templates("wear-m3"),
        fixture = CatalogCutoverProbe.fixture,
      )
    assertFalse(report.ready)
    assertTrue(
      report.findings.single().contains("declares no platform"),
      report.findings.toString(),
    )
    assertNull(report.differences)
  }

  @Test
  fun `a component whose modifiers change is reported`() {
    val kotlin = assertNotNull(executor.synthesisedCatalog("wear-m3"))
    val modified = kotlin.components.first { it.modifierCapabilities.isNotEmpty() }
    val published =
      kotlin
        .newBuilder()
        .also { builder ->
          builder.components =
            kotlin.components.map {
              if (it.componentId != modified.componentId) it
              else it.newBuilder().also { c -> c.modifierCapabilities = emptyList() }.build()
            }
        }
        .build()
    assertTrue(
      CatalogCutoverShadow.catalogDifferences(kotlin, published).any {
        it.startsWith("${modified.componentId}: modifiers: loses")
      }
    )
  }

  // ── What counts as a loss ─────────────────────────────────────────────────────────────────────

  private val wear = assertNotNull(executor.synthesisedCatalog("wear-m3"))

  private fun CatalogCapabilityV1.with(
    componentId: String,
    change: (ComponentCapabilityV1) -> ComponentCapabilityV1,
  ): CatalogCapabilityV1 =
    newBuilder()
      .also { b ->
        b.components = components.map { if (it.componentId == componentId) change(it) else it }
      }
      .build()

  private fun ComponentCapabilityV1.withProperty(
    name: String,
    change: PropertyCapabilityV1.Builder.() -> Unit,
  ): ComponentCapabilityV1 =
    newBuilder()
      .also { b ->
        b.properties = properties.map {
          if (it.name == name) it.newBuilder().apply(change).build() else it
        }
      }
      .build()

  /** The single difference [published] has against [kotlin], which must be the only one. */
  private fun only(kotlin: CatalogCapabilityV1, published: CatalogCapabilityV1) =
    CatalogCutoverShadow.catalogDifferenceDetails(kotlin, published).single()

  private val component =
    wear.components.first { it.properties.isNotEmpty() && it.traits.isNotEmpty() }
  private val property = component.properties.first()

  @Test
  fun `dropping a component, trait or property is a loss, adding one is not`() {
    val dropped = wear.newBuilder().also { it.components = wear.components - component }.build()
    assertTrue(only(wear, dropped).loss)
    assertFalse(only(dropped, wear).loss)

    val fewerTraits =
      wear.with(component.componentId) { c ->
        c.newBuilder().also { it.traits = c.traits.drop(1) }.build()
      }
    assertTrue(only(wear, fewerTraits).loss)
    assertFalse(only(fewerTraits, wear).loss)

    val moreTraits =
      wear.with(component.componentId) { c ->
        c.newBuilder().also { it.traits = c.traits + "ShadowTestTrait" }.build()
      }
    assertFalse(only(wear, moreTraits).loss)
  }

  @Test
  fun `a property type that drops an alternative is a loss, one that adds is not`() {
    val both =
      wear.with(component.componentId) {
        it.withProperty(property.name) {
          jsonType = JsonArray(listOf(JsonPrimitive("boolean"), JsonPrimitive("object")))
        }
      }
    val one =
      wear.with(component.componentId) {
        it.withProperty(property.name) { jsonType = JsonPrimitive("boolean") }
      }
    assertTrue(only(both, one).loss, only(both, one).text)
    assertFalse(only(one, both).loss, only(one, both).text)
  }

  @Test
  fun `becoming required, or narrowing allowed values, is a loss`() {
    val optional =
      wear.with(component.componentId) {
        it.withProperty(property.name) {
          required = false
          allowedValues = emptyList()
        }
      }
    val required =
      optional.with(component.componentId) {
        it.withProperty(property.name) { this.required = true }
      }
    assertTrue(only(optional, required).loss)
    assertFalse(only(required, optional).loss)

    val ab =
      optional.with(component.componentId) {
        it.withProperty(property.name) {
          allowedValues = listOf(JsonPrimitive("a"), JsonPrimitive("b"))
        }
      }
    val a =
      optional.with(component.componentId) {
        it.withProperty(property.name) { allowedValues = listOf(JsonPrimitive("a")) }
      }
    assertTrue(only(ab, a).loss)
    assertFalse(only(a, ab).loss)
    assertTrue(only(optional, a).loss, "any list narrows an unconstrained property")
    assertFalse(only(a, optional).loss, "dropping the list widens it")
  }

  @Test
  fun `narrowing a slot is a loss, widening it is not`() {
    val slotted = wear.components.first { it.slots.isNotEmpty() }
    val slot = slotted.slots.first()
    fun withSlot(min: Int, max: Int?, roles: List<String>) =
      wear.with(slotted.componentId) { c ->
        c.newBuilder()
          .also { b ->
            b.slots =
              c.slots.map {
                if (it.name != slot.name) it
                else
                  it
                    .newBuilder()
                    .also { s ->
                      s.cardinality =
                        SlotCardinalityV1.Builder()
                          .also { k ->
                            k.min = min
                            k.max = max
                          }
                          .build()
                      s.acceptedRoles = roles
                    }
                    .build()
              }
          }
          .build()
      }
    val wide = withSlot(0, null, listOf("Container", "Leaf"))
    assertTrue(only(wide, withSlot(0, 1, listOf("Container", "Leaf"))).loss)
    assertTrue(only(wide, withSlot(1, null, listOf("Container", "Leaf"))).loss)
    assertFalse(only(withSlot(0, 1, listOf("Container", "Leaf")), wide).loss)
    assertTrue(only(wide, withSlot(0, null, listOf("Leaf"))).loss)
    assertFalse(only(withSlot(0, null, listOf("Leaf")), wide).loss)
  }

  private fun CatalogCapabilityV1.superseding(
    entries: Map<String, JsonObject>
  ): CatalogCapabilityV1 =
    newBuilder()
      .also { b ->
        b.statusSemantics = JsonObject(statusSemantics + ("supersedes" to JsonObject(entries)))
      }
      .build()

  private fun successor(componentId: String, variants: JsonObject? = null): JsonObject =
    JsonObject(
      buildMap {
        put("componentId", JsonPrimitive(componentId))
        variants?.let { put("variants", it) }
      }
    )

  /**
   * A design naming a component the published catalog supersedes is moved, not stranded
   * ([planCatalogUpgrade]), so the shadow must not count what the move carries across.
   */
  @Test
  fun `a component the published catalog supersedes is not lost, unless a successor is missing`() {
    val other = wear.components.first { it.componentId != component.componentId }
    val dropped = wear.newBuilder().also { it.components = wear.components - component }.build()

    val superseded =
      dropped.superseding(mapOf(component.componentId to successor(other.componentId)))
    val difference = only(wear, superseded)
    assertFalse(difference.loss, difference.text)
    assertContains(difference.text, "superseded by ${other.componentId}")

    val nowhere = dropped.superseding(mapOf(component.componentId to successor("shadow/missing")))
    val stranded = only(wear, nowhere)
    assertTrue(stranded.loss, stranded.text)
    assertContains(stranded.text, "lacks shadow/missing")
  }

  @Test
  fun `the property a supersedes entry chooses its successor by is migrated, not lost`() {
    val withoutProperty =
      wear.with(component.componentId) { c ->
        c.newBuilder().also { it.properties = c.properties - property }.build()
      }
    assertTrue(only(wear, withoutProperty).loss)

    val variants =
      JsonObject(
        mapOf(
          "property" to JsonPrimitive(property.name),
          "components" to JsonObject(mapOf("a" to JsonPrimitive(component.componentId))),
        )
      )
    val migrated =
      withoutProperty.superseding(
        mapOf(component.componentId to successor(component.componentId, variants))
      )
    val difference = only(wear, migrated)
    assertFalse(difference.loss, difference.text)
    assertContains(difference.text, "migrates ${property.name}")
  }

  @Test
  fun `a changed role is a loss`() {
    val other = if (component.role == "Leaf") "Container" else "Leaf"
    val moved =
      wear.with(component.componentId) { c -> c.newBuilder().also { it.role = other }.build() }
    assertTrue(only(wear, moved).loss)
  }

  @Test
  fun `ready needs no findings and no losses, and gains alone stay ready`() {
    val gains = listOf("wear-m3/x: only the published catalog has it")
    val losses = listOf("wear-m3/y: traits: loses RemoteAuthorable")
    assertTrue(CatalogCutoverShadow.Report("wear-m3", emptyList(), gains, emptyList()).ready)
    assertFalse(CatalogCutoverShadow.Report("wear-m3", emptyList(), gains + losses, losses).ready)
    assertFalse(CatalogCutoverShadow.Report("wear-m3", listOf("finding"), gains, emptyList()).ready)
    assertTrue(CatalogCutoverShadow.Report("glimmer-catalog", emptyList(), null, null).ready)
  }

  @Test
  fun `the report's losses are exactly the differences classified as losses`() {
    val report =
      CatalogCutoverShadow.report(
        catalogId = "wear-m3",
        published = CatalogCutoverFixtures.catalog("wear-m3"),
        templates = CatalogCutoverFixtures.templates("wear-m3"),
        fixture = CatalogCutoverProbe.fixture,
        packComponents = CatalogCutoverFixtures.composed("wear-m3").records,
        exportRecord = CatalogCutoverFixtures.exportRecord("wear-m3"),
        nativeRuntimeId = CatalogCutoverFixtures.rendererRuntimeId("wear-m3"),
      )
    val differences = assertNotNull(report.differences)
    val losses = assertNotNull(report.losses)
    assertTrue(differences.containsAll(losses))
    assertTrue(
      losses.none { it.endsWith("only the published catalog has it") },
      losses.joinToString("\n"),
    )
    assertEquals(report.findings.isEmpty() && losses.isEmpty(), report.ready)
  }
}
