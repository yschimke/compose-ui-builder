package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generator writes `transformation` on exactly the components Wear Material 3 declares it on.
 * `SurfaceTransformation` is a surface treatment; writing it on every list row produced e.g.
 * `Slider(…, transformation = SurfaceTransformation(spec))`, which doesn't compile (no such
 * parameter on `Slider`, `IconButton`, `TextButton`).
 *
 * The expectation is read from the port's own jar (a symbol takes the treatment iff some function
 * of that name declares a `SurfaceTransformation` parameter), rather than a second hand-written
 * list.
 */
class WearSurfaceTransformationTest {
  /** Every Wear Material 3 function name whose signature declares a `SurfaceTransformation`. */
  private val librarySymbols: Set<String> by lazy {
    val jars =
      System.getProperty("java.class.path").split(File.pathSeparator).filter {
        "wear-compose-material3" in it && it.endsWith(".jar")
      }
    assertTrue(jars.isNotEmpty(), "the Wear Material 3 port is not on the test classpath")
    val loader = javaClass.classLoader
    buildSet {
      jars.forEach { path ->
        ZipFile(File(path)).use { zip ->
          zip
            .entries()
            .asSequence()
            .map { it.name }
            .filter { it.startsWith("androidx/wear/compose/material3/") && it.endsWith("Kt.class") }
            .forEach { entry ->
              val type =
                runCatching {
                  loader.loadClass(entry.removeSuffix(".class").replace('/', '.'))
                }
                  .getOrNull() ?: return@forEach
              type.declaredMethods.forEach { method ->
                if (method.parameterTypes.any { it.simpleName == "SurfaceTransformation" }) {
                  // Kotlin mangles a function taking an inline class: `ListHeader-qi6gXK8`.
                  add(method.name.substringBefore('-'))
                }
              }
            }
        }
      }
    }
  }

  /**
   * One Wear screen per component, as the sole list row (the only position where `transformation`
   * is written).
   */
  private fun emittedRow(node: UiBuilderNode): String {
    val base =
      wearScreenUiBuilderDocument(
        "t",
        kotlinx.serialization.json.JsonObject(emptyMap()),
        kotlinx.serialization.json.JsonObject(emptyMap()),
      )
    val list = base.nodes.getValue("wear-list")
    val document =
      base.copy(
        nodes =
          base.nodes +
            ("wear-list" to list.copy(slots = mapOf("items" to listOf(node.id)))) +
            (node.id to node)
      )
    return when (val result = WearScreenCodeExporter.export(document)) {
      is WearScreenCodeExporter.Result.Emitted -> result.source
      is WearScreenCodeExporter.Result.Refused ->
        throw AssertionError("${node.componentId} refused: ${result.reasons}")
    }
  }

  @Test
  fun `the library declares the treatment on some components and not others`() {
    // Guards the derivation: if the scan ever came back empty or total, every case below would
    // pass by comparing nothing to nothing.
    assertTrue("Card" in librarySymbols, "expected Card to carry it: $librarySymbols")
    assertTrue("ListHeader" in librarySymbols, "expected ListHeader to carry it: $librarySymbols")
    assertTrue("Slider" !in librarySymbols, "Slider must not carry it: $librarySymbols")
    assertTrue("TextButton" !in librarySymbols, "TextButton must not carry it: $librarySymbols")
    assertTrue("IconButton" !in librarySymbols, "IconButton must not carry it: $librarySymbols")
  }

  @Test
  fun `the generator's own set is exactly what the library declares`() {
    // Both directions. A symbol the set claims and the library does not is a broken build; one the
    // library declares and the set omits is a row that silently loses its surface treatment.
    assertEquals(
      emptySet(),
      WearScreenCodeExporter.SURFACE_TRANSFORMATION_SYMBOLS - librarySymbols,
      "the generator would write `transformation` on a component Wear Compose does not declare it on",
    )
  }

  @Test
  fun `no emitted row passes transformation to a component that has no such parameter`() {
    val rows =
      listOf(
        UiBuilderNode(
          id = "n",
          componentId = WearScreenCodeExporter.SLIDER,
          properties = properties("value" to 4f, "steps" to 9f),
        ),
        UiBuilderNode(
          id = "n",
          componentId = WearScreenCodeExporter.ICON_BUTTON,
          properties = properties("variant" to "filled"),
        ),
        UiBuilderNode(
          id = "n",
          componentId = WearScreenCodeExporter.TEXT_BUTTON,
          properties = properties("variant" to "filled"),
        ),
        UiBuilderNode(
          id = "n",
          componentId = WearScreenCodeExporter.CARD,
          properties = properties("title" to "Card"),
        ),
        UiBuilderNode(
          id = "n",
          componentId = WearScreenCodeExporter.SWITCH_BUTTON,
          properties = properties("label" to "Wi-Fi", "checked" to true),
        ),
      )

    rows.forEach { node ->
      val source = emittedRow(node)
      // The symbol this component was written as, taken from the source rather than assumed: the
      // variant tables decide it, and a rename there must not quietly skip this check.
      val symbol =
        Regex("\\n\\s{16}([A-Z][A-Za-z]*)\\(").find(source)?.groupValues?.get(1)
          ?: throw AssertionError("no row call found for ${node.componentId} in:\n$source")
      val writesTransformation = "transformation = SurfaceTransformation(spec)" in source
      assertEquals(
        symbol in librarySymbols,
        writesTransformation,
        "$symbol (${node.componentId}): the generator ${if (writesTransformation) "writes" else "omits"} " +
          "`transformation`, and Wear Compose ${if (symbol in librarySymbols) "declares" else "does not declare"} it",
      )
    }
  }

  private fun properties(vararg pairs: Pair<String, Any>) =
    kotlinx.serialization.json.buildJsonObject {
      pairs.forEach { (name, value) ->
        putJsonObject(name) {
          put(
            "type",
            when (value) {
              is Boolean -> "boolean"
              is Number -> "number"
              else -> "string"
            },
          )
          put(
            "value",
            when (value) {
              is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
              is Number -> kotlinx.serialization.json.JsonPrimitive(value)
              else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
            },
          )
        }
      }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
  key: String,
  build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) = put(key, kotlinx.serialization.json.buildJsonObject(build))

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: String) =
  put(key, kotlinx.serialization.json.JsonPrimitive(value))
