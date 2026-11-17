package ee.schimke.composeai.uibuilder.export

/**
 * The properties the published `wff-watchface` catalog declares, per builtin, as the importer needs
 * them: a node may carry only these (the catalog validator refuses any other), so what the importer
 * cannot put in one goes in the declared `wffExtras` string. A test holds this table equal to
 * `wff-watchface-published-v1.json`.
 */
internal object WatchFaceCatalogShape {
  /** A declared property: its catalog `jsonType` and, if the catalog lists any, allowed values. */
  class Declared(val type: String, val allowed: Set<String> = emptySet())

  private fun numbers(vararg names: String) = names.associateWith { Declared("number") }

  private fun strings(vararg names: String) = names.associateWith { Declared("string") }

  private val BOUNDS = numbers("x", "y", "width", "height", "pivotX", "pivotY", "angle", "alpha")
  private val EXTRAS = mapOf(WatchFaceXmlExporter.EXTRAS to Declared("string"))
  private val ALIGN = Declared("string", setOf("START", "CENTER", "END"))

  val DECLARED: Map<String, Map<String, Declared>> =
    mapOf(
      WatchFaceXmlExporter.SCENE to
        mapOf(
          "width" to Declared("integer"),
          "height" to Declared("integer"),
          "clipShape" to Declared("string", setOf("CIRCLE", "RECTANGLE", "NONE")),
          "backgroundColor" to Declared("string"),
          "clockType" to Declared("string", setOf("ANALOG", "DIGITAL")),
          "previewTime" to Declared("string"),
          "wffVersion" to Declared("integer", setOf("1", "2", "3", "4", "5")),
          "verbatim" to Declared("boolean"),
        ) + EXTRAS,
      WatchFaceXmlExporter.GROUP to BOUNDS + strings("name") + EXTRAS,
      WatchFaceXmlExporter.PART_DRAW to
        BOUNDS +
          mapOf(
            "shape" to Declared("string", setOf("ellipse", "rectangle")),
            "fillColor" to Declared("string"),
            "strokeColor" to Declared("string"),
            "strokeWidth" to Declared("number"),
          ) +
          EXTRAS,
      WatchFaceXmlExporter.PART_IMAGE to BOUNDS + strings("resource", "tintColor") + EXTRAS,
      WatchFaceXmlExporter.PART_TEXT to
        BOUNDS +
          mapOf(
            "text" to Declared("string"),
            "fontSize" to Declared("number"),
            "color" to Declared("string"),
            "family" to Declared("string"),
            "align" to ALIGN,
          ) +
          EXTRAS,
      WatchFaceXmlExporter.ANALOG_CLOCK to BOUNDS + EXTRAS,
      WatchFaceXmlExporter.CLOCK_HAND to
        BOUNDS +
          mapOf(
            "type" to Declared("string", setOf("hour", "minute", "second")),
            "resource" to Declared("string"),
            "tintColor" to Declared("string"),
          ) +
          EXTRAS,
      WatchFaceXmlExporter.DIGITAL_CLOCK to BOUNDS + EXTRAS,
      WatchFaceXmlExporter.TIME_TEXT to
        mapOf(
          "format" to Declared("string"),
          "hourFormat" to Declared("string", setOf("SYNC_TO_DEVICE", "12", "24")),
          "align" to ALIGN,
          "fontSize" to Declared("number"),
          "color" to Declared("string"),
          "family" to Declared("string"),
        ) + EXTRAS,
      WatchFaceXmlExporter.COMPLICATION_SLOT to
        BOUNDS +
          mapOf(
            "slotId" to Declared("integer"),
            "supportedTypes" to Declared("string"),
            "boundsType" to Declared("string", setOf("OVAL", "RECTANGLE")),
            "defaultSystemProvider" to Declared("string"),
            "defaultSystemProviderType" to Declared("string"),
          ) +
          EXTRAS,
      WatchFaceXmlExporter.VARIANT to
        mapOf(
          "mode" to Declared("string", setOf("AMBIENT")),
          "target" to Declared("string"),
          "value" to Declared("string"),
        ) + EXTRAS,
      WatchFaceXmlExporter.RAW to mapOf("xml" to Declared("string")),
    )
}
