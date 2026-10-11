package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay

/** One generated Material icon binding shared by validation, rendering, selection and export. */
data class GoogleMaterialIcon(
  val key: String,
  val label: String,
  val composeExpression: String,
  /** False only for the original compatibility keys retained for saved designs. */
  val canonical: Boolean,
)

/**
 * Every style-qualified icon in material-icons-extended plus the original 46 aliases, canonical
 * first, then by label and key. Built lazily; [googleMaterialIcon] lookups do not build it.
 */
val GoogleMaterialIcons: List<GoogleMaterialIcon> by lazy {
  List(GoogleMaterialIconKeys.size) { position ->
    checkNotNull(googleMaterialIconFor(GoogleMaterialIconKeys.keyAt(position)))
  }
}

/** Picker rows exclude compatibility aliases, so one vector never appears twice. */
val SelectableGoogleMaterialIcons: List<GoogleMaterialIcon> by lazy {
  GoogleMaterialIcons.filter(GoogleMaterialIcon::canonical)
}

/** How many icons the picker offers: [SelectableGoogleMaterialIcons]' size, without building it. */
val SelectableGoogleMaterialIconCount: Int
  get() = GoogleMaterialIconKeys.canonicalCount

/**
 * The first [limit] picker icons matching [query], in catalog order, built only for the results.
 * Every word of [query] (split at spaces and dashes) must appear in the key, case-insensitively, in
 * any order, so a pasted label also matches. A blank query is the first page.
 */
fun searchGoogleMaterialIcons(query: String, limit: Int): List<GoogleMaterialIcon> {
  val words = query.split(' ', '-', '—').filter(String::isNotEmpty)
  val results = ArrayList<GoogleMaterialIcon>(minOf(limit, 128))
  for (position in 0 until GoogleMaterialIconKeys.canonicalCount) {
    if (results.size == limit) break
    if (words.all { GoogleMaterialIconKeys.keyContains(position, it) }) {
      results += checkNotNull(googleMaterialIconFor(GoogleMaterialIconKeys.keyAt(position)))
    }
  }
  return results
}

fun googleMaterialIcon(key: String): GoogleMaterialIcon? =
  if (GoogleMaterialIconKeys.positionOf(key) < 0) null else googleMaterialIconFor(key)

/**
 * The catalog's packed keys (`GeneratedGoogleMaterialIconKeys`) and an allocation-free index: two
 * `IntArray`s (key starts and an open-addressed hash table), avoiding a map of ~11,000 strings.
 */
internal object GoogleMaterialIconKeys {
  private val packed: String = GeneratedGoogleMaterialIconKeys.joinToString(separator = "")
  private val starts: IntArray
  private val table: IntArray

  init {
    val count = packed.count { it == '|' }
    starts = IntArray(count + 1)
    var position = 0
    packed.forEachIndexed { index, char -> if (char == '|') starts[++position] = index + 1 }
    var capacity = 1
    while (capacity < count * 2) capacity = capacity shl 1
    table = IntArray(capacity)
    for (entry in 0 until count) {
      var slot = hash(packed, starts[entry], starts[entry + 1] - 1) and (capacity - 1)
      while (table[slot] != 0) slot = (slot + 1) and (capacity - 1)
      table[slot] = entry + 1
    }
  }

  val size: Int
    get() = starts.size - 1

  /** The catalog orders canonical keys (all `style/name`) before the slash-less aliases. */
  val canonicalCount: Int by lazy {
    (0 until size).firstOrNull {
      packed.indexOf('/', starts[it]) !in starts[it] until starts[it + 1]
    } ?: size
  }

  /** Whether the key at [position] contains [needle], ignoring case, compared in place. */
  fun keyContains(position: Int, needle: String): Boolean {
    val start = starts[position]
    val last = starts[position + 1] - 1 - needle.length
    for (from in start..last) {
      if (packed.regionMatches(from, needle, 0, needle.length, ignoreCase = true)) return true
    }
    return false
  }

  fun keyAt(position: Int): String = packed.substring(starts[position], starts[position + 1] - 1)

  /** [key]'s position in the catalog's order, or -1 for a key the catalog does not have. */
  fun positionOf(key: String): Int {
    var slot = hash(key, 0, key.length) and (table.size - 1)
    while (true) {
      val entry = table[slot] - 1
      if (entry < 0) return -1
      val start = starts[entry]
      if (
        starts[entry + 1] - 1 - start == key.length &&
          packed.regionMatches(start, key, 0, key.length)
      ) {
        return entry
      }
      slot = (slot + 1) and (table.size - 1)
    }
  }

  private fun hash(text: String, from: Int, until: Int): Int {
    var h = 0
    for (i in from until until) h = 31 * h + text[i].code
    return h xor (h ushr 16)
  }
}

/**
 * The catalog entry for [key], built from the key alone: `outlined/arrowBack` is
 * `Icons.Outlined.ArrowBack` ("Arrow Back — Outlined"), `autoMirrored/filled/send` is
 * `Icons.AutoMirrored.Filled.Send`, and digit-leading names keep Kotlin's underscore (`_10k`).
 * Compatibility keys carry their own label and target. `MaterialIconCatalogTest` checks every
 * derived entry.
 */
private fun googleMaterialIconFor(key: String): GoogleMaterialIcon? {
  generatedGoogleMaterialIconAlias(key)?.let { (label, expression) ->
    return GoogleMaterialIcon(key, label, expression, canonical = false)
  }
  val parts = key.split('/')
  val mirrored = parts.size == 3 && parts[0] == "autoMirrored"
  if (parts.size != (if (mirrored) 3 else 2)) return null
  val style =
    when (parts[parts.size - 2]) {
      "filled" -> "Filled"
      "outlined" -> "Outlined"
      "rounded" -> "Rounded"
      "sharp" -> "Sharp"
      "twoTone" -> "TwoTone"
      else -> return null
    }
  val name = parts.last().replaceFirstChar { it.uppercaseChar() }
  val member = if (name.first().isDigit()) "_$name" else name
  val receiver = if (mirrored) "AutoMirrored.$style" else style
  val words =
    name
      .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
      .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
  val label =
    words.replaceFirstChar { it.uppercaseChar() } +
      " — " +
      (if (mirrored) "Auto-mirrored $style" else style)
  return GoogleMaterialIcon(key, label, "Icons.$receiver.$member", canonical = true)
}

/**
 * Where an icon's `ImageVector` comes from: compiled builders on the JVM ([cached] answers all),
 * [MaterialIconData] shards in the browser ([cached] answers loaded ones, [load] fetches the rest).
 * Unknown keys are null on both.
 */
expect object GoogleMaterialIconVectors {
  /** The vector for [key] if it can be drawn right now, without waiting. */
  fun cached(key: String): ImageVector?

  /** The vector for [key], fetching its shard first where that is needed. */
  suspend fun load(key: String): ImageVector?
}

/** Resolve one authored icon key if it is ready now; see [rememberGoogleMaterialIconVector]. */
fun googleMaterialIconImageVector(key: String): ImageVector? =
  googleMaterialIcon(key)?.let { GoogleMaterialIconVectors.cached(it.key) }

/**
 * The vector for [key], or null while the browser is still fetching it (callers draw nothing at
 * the icon's size). Immediate on the JVM. Failed fetches retry with backoff up to
 * [ICON_RETRY_MAX_MILLIS] while the icon stays on screen.
 */
@Composable
fun rememberGoogleMaterialIconVector(key: String): ImageVector? {
  var vector by remember(key) { mutableStateOf(googleMaterialIconImageVector(key)) }
  if (vector == null && googleMaterialIcon(key) != null) {
    LaunchedEffect(key) {
      var wait = ICON_RETRY_FIRST_MILLIS
      while (true) {
        val loaded = GoogleMaterialIconVectors.load(key)
        if (loaded != null) {
          vector = loaded
          break
        }
        delay(wait)
        wait = (wait * 2).coerceAtMost(ICON_RETRY_MAX_MILLIS)
      }
    }
  }
  return vector
}

private const val ICON_RETRY_FIRST_MILLIS = 1_000L
private const val ICON_RETRY_MAX_MILLIS = 30_000L
