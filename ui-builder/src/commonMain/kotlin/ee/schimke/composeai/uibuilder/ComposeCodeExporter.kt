package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog

/**
 * Deterministic, deliberately conservative exporter for the first supported native screen slice.
 */
object ComposeCodeExporter {
  /** Preferred capability-gated whole-document export path. */
  fun export(
    document: UiBuilderDocument,
    catalog: CapabilityCatalog,
  ): ComposeExportResult = CapabilityComposeCodeExporter.export(document, catalog)

  /** Legacy compact Confetti spike retained as a baseline while callers migrate to capabilities. */
  fun export(document: UiBuilderDocument): String {
    require(document.roots.size == 1) { "code export currently requires one root" }
    val root = document.nodes.getValue(document.roots.single())
    require(root.componentId == "layout/scaffold") { "code export currently requires a Scaffold" }
    val title = root.slot(document, "topBar").single().slot(document, "title").single().text()
    val content = root.slot(document, "content").single()
    val contentChildren = content.slot(document, "children")
    val row = contentChildren.single { it.componentId == "layout/lazy-row" }
    val chips = row.slot(document, "items")
    val tabs =
      contentChildren.single { it.componentId == "m3/primary-tab-row" }.slot(document, "tabs")
    val schedule =
      contentChildren.single { it.componentId == "layout/lazy-column" }.slot(document, "items")

    return buildString {
      appendLine("@Composable")
      appendLine("fun ConfettiScheduleHeader() {")
      appendLine("  var selectedTrack by remember { mutableStateOf<String?>(null) }")
      appendLine("  Scaffold(")
      appendLine("    topBar = {")
      appendLine(
        "      CenterAlignedTopAppBar(title = { Text(\"${title.escape()}\", style = MaterialTheme.typography.titleLarge) })"
      )
      appendLine("    },")
      appendLine("  ) { contentPadding ->")
      appendLine("    Column(Modifier.padding(contentPadding).fillMaxSize()) {")
      appendLine("      LazyRow(")
      appendLine("        horizontalArrangement = Arrangement.spacedBy(8.dp),")
      appendLine("        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),")
      appendLine("      ) {")
      chips.forEach { chip ->
        val value = chip.selectionValue()
        val condition =
          value?.let { "selectedTrack == \"${it.escape()}\"" } ?: "selectedTrack == null"
        val assignment = value?.let { "if ($condition) null else \"${it.escape()}\"" } ?: "null"
        appendLine("        item {")
        appendLine("          FilterChip(")
        appendLine("            selected = $condition,")
        appendLine("            onClick = { selectedTrack = $assignment },")
        appendLine(
          "            label = { Text(\"${chip.slot(document, "label").single().text().escape()}\") },"
        )
        val leading = chip.slot(document, "leadingIcon")
        if (leading.isNotEmpty()) {
          val dot = leading.single()
          appendLine("            leadingIcon = {")
          appendLine(
            "              Box(Modifier.size(8.dp).background(Color(${dot.colorLiteral()})))"
          )
          appendLine("            },")
        }
        appendLine("          )")
        appendLine("        }")
      }
      appendLine("      }")
      appendLine("      PrimaryTabRow(selectedTabIndex = 0) {")
      tabs.forEach { tab ->
        appendLine("        Tab(")
        appendLine("          selected = ${tab.boolValue("selected")},")
        appendLine("          onClick = { /* set selectedDay */ },")
        appendLine(
          "          text = { Text(\"${tab.slot(document, "text").single().text().escape()}\") },"
        )
        appendLine("        )")
      }
      appendLine("      }")
      appendLine("      LazyColumn(Modifier.fillMaxWidth().weight(1f)) {")
      schedule.forEach { item ->
        when {
          item.id.startsWith("time-") ->
            appendLine(
              "        item { ScheduleTimeHeader(\"${item.slot(document, "content").single().slot(document, "children").single { it.componentId == "m3/text" }.text().escape()}\") }"
            )
          item.componentId == "m3/list-item" -> {
            val supporting = item.slot(document, "supporting").single().slot(document, "children")
            appendLine("        item {")
            appendLine("          ScheduleSessionItem(")
            appendLine(
              "            headline = \"${item.slot(document, "headline").single().text().escape()}\","
            )
            appendLine("            speaker = \"${supporting[0].text().escape()}\",")
            appendLine("            metadata = \"${supporting[1].text().escape()}\",")
            val bookmarked =
              item.slot(document, "trailing").single().propertyValue("iconKey") == "bookmark"
            appendLine("            bookmarked = $bookmarked,")
            appendLine("          )")
            appendLine("        }")
          }
          item.componentId == "m3/horizontal-divider" ->
            appendLine("        item { HorizontalDivider(Modifier.padding(start = 16.dp)) }")
          item.id == "coffee-break" ->
            appendLine(
              "        item { ScheduleBreak(title = \"Coffee Break\", location = \"Foyer · Level 1\") }"
            )
        }
      }
      appendLine("      }")
      appendLine("    }")
      appendLine("  }")
      appendLine("}")
    }
      .trimEnd() + "\n"
  }
}

private fun UiBuilderNode.slot(document: UiBuilderDocument, name: String): List<UiBuilderNode> =
  slots[name].orEmpty().map(document.nodes::getValue)

private fun UiBuilderNode.text(): String = propertyValue("text")

private fun UiBuilderNode.boolValue(name: String): Boolean =
  properties.getValue(name).jsonObject.getValue("value").jsonPrimitive.content.toBoolean()

private fun UiBuilderNode.selectionValue(): String? =
  properties["selected"]?.jsonObject?.get("value")?.let {
    if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content
  }

private fun UiBuilderNode.colorLiteral(): String = "0x" + propertyValue("color").removePrefix("#")

private fun UiBuilderNode.propertyValue(name: String): String =
  properties.getValue(name).jsonObject.getValue("value").jsonPrimitive.content

private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private val kotlinx.serialization.json.JsonElement.jsonObject
  get() = this as kotlinx.serialization.json.JsonObject

private val kotlinx.serialization.json.JsonElement.jsonPrimitive
  get() = this as kotlinx.serialization.json.JsonPrimitive
