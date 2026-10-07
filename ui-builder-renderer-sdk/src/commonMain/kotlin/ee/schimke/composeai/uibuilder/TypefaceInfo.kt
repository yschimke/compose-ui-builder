package ee.schimke.composeai.uibuilder

/**
 * What a typeface offers a text's [ee.schimke.composeai.uibuilder.export.FontSettings]: the
 * variation axes it has, each with its range, and the OpenType layout features it carries.
 *
 * Read from the font file itself, so the inspector offers exactly what the face the text is drawn
 * in can do, rather than a fixed list that is right for Roboto Flex and wrong for everything else.
 */
data class TypefaceInfo(val axes: List<TypefaceAxis>, val features: List<String>) {
  val isVariable: Boolean
    get() = axes.isNotEmpty()

  /** The union of two files of one family: a static family ships one file per weight. */
  operator fun plus(other: TypefaceInfo): TypefaceInfo =
    TypefaceInfo(
      axes = (axes + other.axes).distinctBy { it.tag },
      features = (features + other.features).distinct().sorted(),
    )

  companion object {
    val EMPTY: TypefaceInfo = TypefaceInfo(emptyList(), emptyList())
  }
}

/** One variation axis: its tag, the name the font gives it, and its range. */
data class TypefaceAxis(
  val tag: String,
  val name: String,
  val min: Float,
  val default: Float,
  val max: Float,
  /** The font flags it hidden: an axis for the font's own machinery, not for an author. */
  val hidden: Boolean = false,
)

/**
 * Read [data], a TrueType or OpenType font (or the first face of a collection), into its
 * [TypefaceInfo]: the `fvar` axes, named from the `name` table, and the `GSUB` and `GPOS` feature
 * tags. Anything unreadable reads as nothing rather than failing: a font the inspector cannot read
 * still draws.
 */
fun readTypefaceInfo(data: ByteArray): TypefaceInfo = runCatching {
  OpenTypeReader(data).read()
}
  .getOrDefault(TypefaceInfo.EMPTY)

private class OpenTypeReader(private val data: ByteArray) {
  private fun u8(at: Int): Int = data[at].toInt() and 0xFF

  private fun u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)

  private fun s32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)

  private fun fixed(at: Int): Float = s32(at) / 65536f

  private fun tag(at: Int): String = (0 until 4).map { u8(at + it).toChar() }.joinToString("")

  fun read(): TypefaceInfo {
    // A collection (`ttcf`) names its faces' offset tables; the first face is the one a platform
    // font built from these bytes draws.
    val base = if (tag(0) == "ttcf") s32(12) else 0
    val count = u16(base + 4)
    val tables =
      (0 until count).associate { index ->
        val record = base + 12 + index * 16
        tag(record) to s32(record + 8)
      }
    val names = tables["name"]?.let(::names).orEmpty()
    val axes = tables["fvar"]?.let { axes(it, names) }.orEmpty()
    val features =
      listOfNotNull(tables["GSUB"], tables["GPOS"]).flatMap(::features).distinct().sorted()
    return TypefaceInfo(axes, features)
  }

  private fun axes(at: Int, names: Map<Int, String>): List<TypefaceAxis> {
    val axesOffset = u16(at + 4)
    val axisCount = u16(at + 8)
    val axisSize = u16(at + 10)
    return (0 until axisCount).map { index ->
      val axis = at + axesOffset + index * axisSize
      val tag = tag(axis)
      TypefaceAxis(
        tag = tag,
        name = names[u16(axis + 18)] ?: tag,
        min = fixed(axis + 4),
        default = fixed(axis + 8),
        max = fixed(axis + 12),
        hidden = (u16(axis + 16) and 0x1) != 0,
      )
    }
  }

  private fun features(at: Int): List<String> {
    val list = at + u16(at + 6)
    return (0 until u16(list)).map { tag(list + 2 + it * 6) }
  }

  /**
   * The `name` table's strings by id, preferring Windows Unicode English, then any Windows Unicode,
   * then Macintosh Roman.
   */
  private fun names(at: Int): Map<Int, String> {
    val count = u16(at + 2)
    val storage = at + u16(at + 4)
    val found = mutableMapOf<Int, Pair<Int, String>>()
    for (index in 0 until count) {
      val record = at + 6 + index * 12
      val platform = u16(record)
      val encoding = u16(record + 2)
      val language = u16(record + 4)
      val id = u16(record + 6)
      val length = u16(record + 8)
      val offset = storage + u16(record + 10)
      val (rank, text) =
        when {
          platform == 3 && (encoding == 1 || encoding == 10) ->
            (if (language == 0x409) 0 else 1) to utf16(offset, length)
          platform == 0 -> 1 to utf16(offset, length)
          platform == 1 && encoding == 0 -> 2 to ascii(offset, length)
          else -> continue
        }
      val current = found[id]
      if (current == null || rank < current.first) found[id] = rank to text
    }
    return found.mapValues { it.value.second }
  }

  private fun utf16(at: Int, length: Int): String =
    CharArray(length / 2) { u16(at + it * 2).toChar() }.concatToString()

  private fun ascii(at: Int, length: Int): String =
    CharArray(length) { u8(at + it).toChar() }.concatToString()
}
