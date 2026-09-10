import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

private data class MaterialIconEntry(
  val key: String,
  val label: String,
  val expression: String,
  val importName: String,
  val canonical: Boolean,
)

private fun RegularFileProperty.readInventory(): List<MaterialIconEntry> =
  get()
    .asFile
    .readLines()
    .filterNot { it.isBlank() || it.startsWith("#") }
    .map { line ->
      val fields = line.split('\t')
      check(fields.size == 5) { "Malformed Material icon inventory row: $line" }
      MaterialIconEntry(fields[0], fields[1], fields[2], fields[3], fields[4].toBooleanStrict())
    }

abstract class GenerateMaterialIconInventory : DefaultTask() {
  @get:Classpath abstract val iconClasspath: ConfigurableFileCollection

  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun generate() {
    val jars =
      iconClasspath.files
        .filter {
          it.name.startsWith("material-icons-extended-desktop-") ||
            it.name.startsWith("material-icons-core-desktop-")
        }
        .sortedBy { it.name }
    check(jars.size == 2) {
      "material-icons core and extended desktop jars are missing from ${iconClasspath.files}"
    }
    val pattern =
      Regex(
        "androidx/compose/material/icons/(automirrored/)?" +
          "(filled|outlined|rounded|sharp|twotone)/([A-Za-z0-9_]+)Kt\\.class"
      )
    val canonical = jars.flatMap { jar ->
      ZipFile(jar).use { zip ->
        zip
          .entries()
          .asSequence()
          .mapNotNull { pattern.matchEntire(it.name) }
          .map { match ->
            val mirrored = match.groupValues[1].isNotEmpty()
            val packageStyle = match.groupValues[2]
            val member = match.groupValues[3]
            val style =
              when (packageStyle) {
                "filled" -> "Filled"
                "outlined" -> "Outlined"
                "rounded" -> "Rounded"
                "sharp" -> "Sharp"
                "twotone" -> "TwoTone"
                else -> error("unsupported icon style $packageStyle")
              }
            val styleKey = if (packageStyle == "twotone") "twoTone" else packageStyle
            val plainName = member.removePrefix("_")
            val wireName = plainName.replaceFirstChar { it.lowercase() }
            val receiver = if (mirrored) "AutoMirrored.$style" else style
            val key = if (mirrored) "autoMirrored/$styleKey/$wireName" else "$styleKey/$wireName"
            val words =
              plainName
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            val label =
              words.replaceFirstChar { it.uppercase() } +
                " — " +
                (if (mirrored) "Auto-mirrored $style" else style)
            val packageName =
              "androidx.compose.material.icons." +
                (if (mirrored) "automirrored/" else "") +
                packageStyle
            MaterialIconEntry(
              key = key,
              label = label,
              expression = "Icons.$receiver.$member",
              importName = packageName.replace('/', '.') + ".$member",
              canonical = true,
            )
          }
          .toList()
      }
    }
    check(canonical.size == 11_385) {
      "Expected the shipped 11,385 Material icon vectors, found ${canonical.size} in $jars"
    }
    check(canonical.map { it.key }.distinct().size == canonical.size) {
      "Style-qualified Material icon keys are not unique"
    }

    val byExpression = canonical.associateBy(MaterialIconEntry::expression)
    val aliases = LEGACY_ALIASES.map { (key, value) ->
      val (label, expression) = value
      val target = checkNotNull(byExpression[expression]) { "$key points at missing $expression" }
      target.copy(key = key, label = label, canonical = false)
    }
    val entries = (canonical + aliases).sortedBy(MaterialIconEntry::key)
    val file = output.get().asFile
    file.parentFile.mkdirs()
    file.writeText(
      buildString {
        appendLine(
          "# Generated from ${jars.joinToString { it.name }} by " +
            "generateMaterialIconInventory. Do not edit."
        )
        appendLine("# key\tlabel\tIcons member\textension property import\tcanonical")
        entries.forEach { entry ->
          appendLine(
            listOf(
                entry.key,
                entry.label,
                entry.expression,
                entry.importName,
                entry.canonical.toString(),
              )
              .joinToString("\t")
          )
        }
      }
    )
  }

  private companion object {
    val LEGACY_ALIASES =
      mapOf(
        "accessTime" to ("Access time" to "Icons.Filled.AccessTime"),
        "accountCircle" to ("Account circle" to "Icons.Filled.AccountCircle"),
        "add" to ("Add" to "Icons.Filled.Add"),
        "addCircle" to ("Add circle" to "Icons.Filled.AddCircle"),
        "arrowBack" to ("Arrow back" to "Icons.AutoMirrored.Filled.ArrowBack"),
        "arrowForward" to ("Arrow forward" to "Icons.AutoMirrored.Filled.ArrowForward"),
        "bookmark" to ("Bookmark" to "Icons.Filled.Bookmark"),
        "bookmarkBorder" to ("Bookmark border" to "Icons.Outlined.BookmarkBorder"),
        "calendarMonth" to ("Calendar month" to "Icons.Filled.CalendarMonth"),
        "cameraAlt" to ("Camera" to "Icons.Filled.CameraAlt"),
        "check" to ("Check" to "Icons.Filled.Check"),
        "checkCircle" to ("Check circle" to "Icons.Filled.CheckCircle"),
        "chevronRight" to ("Chevron right" to "Icons.Filled.ChevronRight"),
        "close" to ("Close" to "Icons.Filled.Close"),
        "coffee" to ("Coffee" to "Icons.Filled.Coffee"),
        "delete" to ("Delete" to "Icons.Filled.Delete"),
        "download" to ("Download" to "Icons.Filled.Download"),
        "edit" to ("Edit" to "Icons.Filled.Edit"),
        "email" to ("Email" to "Icons.Filled.Email"),
        "expandMore" to ("Expand more" to "Icons.Filled.ExpandMore"),
        "favorite" to ("Favorite" to "Icons.Filled.Favorite"),
        "genres" to ("Genres" to "Icons.Filled.Category"),
        "home" to ("Home" to "Icons.Filled.Home"),
        "image" to ("Image" to "Icons.Filled.Image"),
        "info" to ("Info" to "Icons.Filled.Info"),
        "locationOn" to ("Location" to "Icons.Filled.LocationOn"),
        "lock" to ("Lock" to "Icons.Filled.Lock"),
        "menu" to ("Menu" to "Icons.Filled.Menu"),
        "moreVert" to ("More vertically" to "Icons.Filled.MoreVert"),
        "notifications" to ("Notifications" to "Icons.Filled.Notifications"),
        "pauseCircle" to ("Pause circle" to "Icons.Filled.PauseCircle"),
        "person" to ("Person" to "Icons.Filled.Person"),
        "phone" to ("Phone" to "Icons.Filled.Phone"),
        "playCircle" to ("Play circle" to "Icons.Filled.PlayCircle"),
        "playlistAdd" to ("Playlist add" to "Icons.AutoMirrored.Filled.PlaylistAdd"),
        "refresh" to ("Refresh" to "Icons.Filled.Refresh"),
        "remove" to ("Remove" to "Icons.Filled.Remove"),
        "search" to ("Search" to "Icons.Filled.Search"),
        "settings" to ("Settings" to "Icons.Filled.Settings"),
        "share" to ("Share" to "Icons.Filled.Share"),
        "star" to ("Star" to "Icons.Filled.Star"),
        "stopCircle" to ("Stop circle" to "Icons.Filled.StopCircle"),
        "upload" to ("Upload" to "Icons.Filled.Upload"),
        "videoLibrary" to ("Video library" to "Icons.Filled.VideoLibrary"),
        "visibility" to ("Visibility" to "Icons.Filled.Visibility"),
        "warning" to ("Warning" to "Icons.Filled.Warning"),
      )
  }
}

abstract class GenerateMaterialIconUiSources : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inventory: RegularFileProperty

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val entries = inventory.readInventory()
    val directory = outputDirectory.get().asFile
    directory.deleteRecursively()
    val packageDirectory = directory.resolve("ee/schimke/composeai/uibuilder")
    packageDirectory.mkdirs()
    val groups = entries.groupBy { it.expression.substringBeforeLast('.') }
    val groupNames = mutableListOf<String>()
    groups.toSortedMap().forEach { (receiver, icons) ->
      val groupName = receiver.removePrefix("Icons.").replace(".", "")
      groupNames += "generated${groupName}GoogleMaterialIcons"
      packageDirectory
        .resolve("GeneratedGoogleMaterialIcons$groupName.kt")
        .writeText(
          buildString {
            appendLine("@file:Suppress(\"DEPRECATION\")")
            appendLine()
            appendLine("package ee.schimke.composeai.uibuilder")
            appendLine()
            appendLine("import androidx.compose.material.icons.Icons")
            icons.map(MaterialIconEntry::importName).distinct().sorted().forEach {
              appendLine("import $it")
            }
            appendLine()
            appendLine(
              "// Generated from the shipped material-icons-extended artifact. Do not edit."
            )
            icons.chunked(80).forEachIndexed { index, chunk ->
              appendLine("private fun generated${groupName}Chunk$index() = listOf(")
              chunk.forEach { icon ->
                appendLine(
                  "  GoogleMaterialIcon(${icon.key.quoted()}, ${icon.label.quoted()}, " +
                    "${icon.expression.quoted()}, ${icon.canonical}),"
                )
              }
              appendLine(")")
            }
            appendLine()
            appendLine(
              "internal val generated${groupName}GoogleMaterialIcons: List<GoogleMaterialIcon> by lazy {"
            )
            appendLine("  buildList {")
            icons.chunked(80).indices.forEach {
              appendLine("    addAll(generated${groupName}Chunk$it())")
            }
            appendLine("  }")
            appendLine("}")
            appendLine()
            icons
              .groupBy { it.key.hashCode() and 31 }
              .toSortedMap()
              .forEach { (bucket, bucketIcons) ->
                appendLine(
                  "private fun generated${groupName}ImageVector$bucket(key: String) = when (key) {"
                )
                bucketIcons.forEach { icon ->
                  appendLine("  ${icon.key.quoted()} -> ${icon.expression}")
                }
                appendLine("  else -> null")
                appendLine("}")
              }
            appendLine()
            appendLine(
              "internal fun generated${groupName}ImageVector(key: String) = when (key.hashCode() and 31) {"
            )
            icons
              .groupBy { it.key.hashCode() and 31 }
              .keys
              .sorted()
              .forEach { bucket ->
                appendLine("  $bucket -> generated${groupName}ImageVector$bucket(key)")
              }
            appendLine("  else -> null")
            appendLine("}")
          }
        )
    }
    packageDirectory
      .resolve("GeneratedGoogleMaterialIcons.kt")
      .writeText(
        buildString {
          appendLine("package ee.schimke.composeai.uibuilder")
          appendLine()
          appendLine("// Generated from the shipped material-icons-extended artifact. Do not edit.")
          appendLine(
            "internal val GeneratedGoogleMaterialIcons: List<GoogleMaterialIcon> by lazy {"
          )
          appendLine("  buildList {")
          groupNames.forEach { appendLine("    addAll($it)") }
          appendLine("  }")
          appendLine("}")
          appendLine()
          appendLine("internal fun generatedGoogleMaterialIconImageVector(key: String) =")
          appendLine(
            "  " +
              groupNames.joinToString(" ?:\n    ") {
                it.removePrefix("generated").removeSuffix("GoogleMaterialIcons").let { groupName ->
                  "generated${groupName}ImageVector(key)"
                }
              }
          )
        }
      )
  }
}

abstract class GenerateMaterialIconExportSource : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inventory: RegularFileProperty

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun generate() {
    val entries = inventory.readInventory()
    val directory =
      outputDirectory.get().asFile.resolve("ee/schimke/composeai/uibuilder/export").also {
        it.mkdirs()
      }
    directory
      .resolve("GeneratedMaterialIconMembers.kt")
      .writeText(
        buildString {
          appendLine("package ee.schimke.composeai.uibuilder.export")
          appendLine()
          appendLine("// Generated from the shipped material-icons-extended artifact. Do not edit.")
          entries.chunked(100).forEachIndexed { index, chunk ->
            appendLine("private fun generatedMaterialIconMembersChunk$index() = mapOf(")
            chunk.forEach {
              appendLine(
                "  ${it.key.quoted()} to ${it.expression.removePrefix("Icons.").quoted()},"
              )
            }
            appendLine(")")
          }
          appendLine()
          appendLine("internal val GeneratedMaterialIconMembers: Map<String, String> by lazy {")
          appendLine("  buildMap {")
          entries.chunked(100).indices.forEach {
            appendLine("    putAll(generatedMaterialIconMembersChunk$it())")
          }
          appendLine("  }")
          appendLine("}")
        }
      )
  }
}

abstract class GenerateMaterialIconCatalogFixture : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val inventory: RegularFileProperty

  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val catalog: RegularFileProperty

  @get:OutputFile abstract val output: RegularFileProperty

  @TaskAction
  fun generate() {
    val keys = inventory.readInventory().map(MaterialIconEntry::key)
    val replacement = keys.joinToString(prefix = "[", postfix = "]") { it.quoted() }
    val source = catalog.get().asFile.readText()
    val iconNames = Regex("\"name\"\\s*:\\s*\"iconKey\"").findAll(source).toList()
    check(iconNames.size == 1) { "Expected one iconKey property, found ${iconNames.size}" }
    val iconName = iconNames.single()
    val objectStart = source.lastIndexOf('{', iconName.range.first)
    val objectEnd = source.indexOf('}', iconName.range.last)
    check(objectStart >= 0 && objectEnd > objectStart) { "Could not bound the iconKey property" }
    val property = source.substring(objectStart, objectEnd + 1)
    val allowedValues = Regex("(\"allowedValues\"\\s*:\\s*)\\[[^]]*]")
    check(allowedValues.findAll(property).count() == 1) {
      "Expected one allowedValues array on iconKey"
    }
    val updatedProperty = allowedValues.replace(property, "$1$replacement")
    val file = output.get().asFile
    file.parentFile.mkdirs()
    file.writeText(source.replaceRange(objectStart, objectEnd + 1, updatedProperty))
  }
}

abstract class VerifyMatchingFile : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val checkedIn: RegularFileProperty

  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val expected: RegularFileProperty

  @TaskAction
  fun verify() {
    check(checkedIn.get().asFile.readBytes().contentEquals(expected.get().asFile.readBytes())) {
      "The checked-in Material icon catalog is stale. Run " +
        "./gradlew updateMaterialIconCatalogFixture and read the diff."
    }
  }
}

abstract class UpdateMaterialIconCatalogFixtures : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val generatedM3: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val generatedWear: RegularFileProperty

  // Deliberately not task outputs: the generator reads the checked-in files as templates, so
  // declaring them here would manufacture a generate -> update -> generate dependency cycle.
  @get:Internal abstract val checkedInM3: RegularFileProperty

  @get:Internal abstract val checkedInWear: RegularFileProperty

  @TaskAction
  fun update() {
    generatedM3.get().asFile.copyTo(checkedInM3.get().asFile, overwrite = true)
    generatedWear.get().asFile.copyTo(checkedInWear.get().asFile, overwrite = true)
  }
}

private fun String.quoted(): String = buildString {
  append('"')
  this@quoted.forEach { character ->
    when (character) {
      '\\' -> append("\\\\")
      '"' -> append("\\\"")
      '$' -> append("\\$")
      else -> append(character)
    }
  }
  append('"')
}
