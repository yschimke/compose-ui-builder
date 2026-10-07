package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.DefaultFillType
import androidx.compose.ui.graphics.vector.DefaultGroupName
import androidx.compose.ui.graphics.vector.DefaultPivotX
import androidx.compose.ui.graphics.vector.DefaultPivotY
import androidx.compose.ui.graphics.vector.DefaultRotation
import androidx.compose.ui.graphics.vector.DefaultScaleX
import androidx.compose.ui.graphics.vector.DefaultScaleY
import androidx.compose.ui.graphics.vector.DefaultTranslationX
import androidx.compose.ui.graphics.vector.DefaultTranslationY
import androidx.compose.ui.graphics.vector.DefaultTrimPathEnd
import androidx.compose.ui.graphics.vector.DefaultTrimPathOffset
import androidx.compose.ui.graphics.vector.DefaultTrimPathStart
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Material icons as data: the browser build's replacement for compiling them.
 *
 * `material-icons-extended` is ~11,000 Kotlin functions, one `ImageVector` builder per icon per
 * style, and it was 58% of the editor's Wasm code: downloaded, compiled and held by every tab
 * whether or not a design drew a single icon. The JVM keeps those builders. The browser gets the
 * same vectors as JSON, written from them by `MaterialIconDataGenerator` and split into files of
 * [SHARD_SIZE] icons, so drawing one icon fetches one small file.
 *
 * The format is only as rich as Material icons need: paths with solid fills and strokes, alphas and
 * fill type, nested groups with their transforms and clip, and the vector's own size and mirroring.
 * [encode] refuses anything else (a gradient, a trimmed path, a tint), so an icon artifact that
 * starts using them fails the build rather than drawing wrong. `MaterialIconDataTest` round-trips
 * every icon and requires the result to equal the compiled `ImageVector`.
 */
object MaterialIconData {
  /** Icons per file. */
  const val SHARD_SIZE: Int = 180

  /**
   * Which file holds [key]: its position in [GoogleMaterialIcons], the catalog's own order (label,
   * then key), which the build compiles into every platform and writes the files from. Neighbours
   * in the picker are neighbours here, so its first page is one fetch; a hash would have scattered
   * those 80 icons over nearly every file. Null for a key the catalog does not have.
   */
  fun shardOf(key: String): Int? =
    GoogleMaterialIconKeys.positionOf(key).takeIf { it >= 0 }?.let { it / SHARD_SIZE }

  val shardCount: Int
    get() = (GoogleMaterialIconKeys.size + SHARD_SIZE - 1) / SHARD_SIZE

  fun shardFileName(shard: Int): String = "icons-$shard.json"

  /** One shard's file: icon key to vector. */
  fun encodeShard(icons: Map<String, ImageVector>): String =
    json.encodeToString(Shard.serializer(), Shard(icons.mapValues { (_, v) -> encode(v) }))

  fun decodeShard(text: String): Map<String, ImageVector> =
    readShard(text).mapValues { (_, v) -> decode(v) }

  /** A shard's icons still as path text: what a browser keeps until an icon is drawn. */
  internal fun readShard(text: String): Map<String, IconVector> =
    json.decodeFromString(Shard.serializer(), text).icons

  internal fun encode(vector: ImageVector): IconVector {
    require(vector.tintColor == Color.Unspecified && vector.tintBlendMode == BlendMode.SrcIn) {
      "${vector.name}: a tinted vector is not representable"
    }
    require(vector.root.name == DefaultGroupName && vector.root.isPlain()) {
      "${vector.name}: a transformed root group is not representable"
    }
    return IconVector(
      name = vector.name,
      width = vector.defaultWidth.value,
      height = vector.defaultHeight.value,
      viewportWidth = vector.viewportWidth,
      viewportHeight = vector.viewportHeight,
      autoMirror = vector.autoMirror,
      children = vector.root.map { it.encode(vector.name) },
    )
  }

  internal fun decode(icon: IconVector): ImageVector {
    val builder =
      ImageVector.Builder(
        name = icon.name,
        defaultWidth = icon.width.dp,
        defaultHeight = icon.height.dp,
        viewportWidth = icon.viewportWidth,
        viewportHeight = icon.viewportHeight,
        autoMirror = icon.autoMirror,
      )
    icon.children.forEach { builder.add(it) }
    return builder.build()
  }

  private fun ImageVector.Builder.add(node: IconNode) {
    val children = node.children
    if (children == null) {
      addPath(
        pathData = parsePath(node.path.orEmpty()),
        pathFillType = if (node.evenOdd) PathFillType.EvenOdd else DefaultFillType,
        name = node.name,
        fill = node.fill?.let { SolidColor(Color(it.toInt())) },
        fillAlpha = node.fillAlpha,
        stroke = node.stroke?.let { SolidColor(Color(it.toInt())) },
        strokeAlpha = node.strokeAlpha,
        strokeLineWidth = node.strokeWidth,
        strokeLineCap = strokeCaps[node.strokeCap],
        strokeLineJoin = strokeJoins[node.strokeJoin],
        strokeLineMiter = node.strokeMiter,
      )
      return
    }
    addGroup(
      name = node.name,
      rotate = node.rotation,
      pivotX = node.pivotX,
      pivotY = node.pivotY,
      scaleX = node.scaleX,
      scaleY = node.scaleY,
      translationX = node.translationX,
      translationY = node.translationY,
      clipPathData = parsePath(node.clip),
    )
    children.forEach { add(it) }
    clearGroup()
  }

  private fun VectorNode.encode(icon: String): IconNode =
    when (this) {
      is VectorPath -> {
        require(
          trimPathStart == DefaultTrimPathStart &&
            trimPathEnd == DefaultTrimPathEnd &&
            trimPathOffset == DefaultTrimPathOffset
        ) {
          "$icon: a trimmed path is not representable"
        }
        IconNode(
          name = name,
          path = pathData.toPathString(),
          evenOdd = pathFillType == PathFillType.EvenOdd,
          fill = fill?.solidArgb(icon),
          fillAlpha = fillAlpha,
          stroke = stroke?.solidArgb(icon),
          strokeAlpha = strokeAlpha,
          strokeWidth = strokeLineWidth,
          strokeCap = strokeCaps.indexOf(strokeLineCap),
          strokeJoin = strokeJoins.indexOf(strokeLineJoin),
          strokeMiter = strokeLineMiter,
        )
      }
      is VectorGroup ->
        IconNode(
          name = name,
          rotation = rotation,
          pivotX = pivotX,
          pivotY = pivotY,
          scaleX = scaleX,
          scaleY = scaleY,
          translationX = translationX,
          translationY = translationY,
          clip = clipPathData.toPathString(),
          children = map { it.encode(icon) },
        )
    }

  private val strokeCaps = listOf(StrokeCap.Butt, StrokeCap.Round, StrokeCap.Square)
  private val strokeJoins = listOf(StrokeJoin.Miter, StrokeJoin.Round, StrokeJoin.Bevel)

  private fun VectorGroup.isPlain() =
    rotation == DefaultRotation &&
      pivotX == DefaultPivotX &&
      pivotY == DefaultPivotY &&
      scaleX == DefaultScaleX &&
      scaleY == DefaultScaleY &&
      translationX == DefaultTranslationX &&
      translationY == DefaultTranslationY &&
      clipPathData.isEmpty()

  private fun Brush.solidArgb(icon: String): Long {
    require(this is SolidColor) { "$icon: only solid fills are representable, not $this" }
    return value.toArgb().toLong() and 0xFFFFFFFFL
  }

  /** SVG path syntax, one command letter per node so a parse gives back the same node types. */
  internal fun List<PathNode>.toPathString(): String = buildString {
    for (node in this@toPathString) {
      when (node) {
        PathNode.Close -> append('Z')
        is PathNode.MoveTo -> command('M', node.x, node.y)
        is PathNode.RelativeMoveTo -> command('m', node.dx, node.dy)
        is PathNode.LineTo -> command('L', node.x, node.y)
        is PathNode.RelativeLineTo -> command('l', node.dx, node.dy)
        is PathNode.HorizontalTo -> command('H', node.x)
        is PathNode.RelativeHorizontalTo -> command('h', node.dx)
        is PathNode.VerticalTo -> command('V', node.y)
        is PathNode.RelativeVerticalTo -> command('v', node.dy)
        is PathNode.CurveTo -> command('C', node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
        is PathNode.RelativeCurveTo ->
          command('c', node.dx1, node.dy1, node.dx2, node.dy2, node.dx3, node.dy3)
        is PathNode.ReflectiveCurveTo -> command('S', node.x1, node.y1, node.x2, node.y2)
        is PathNode.RelativeReflectiveCurveTo ->
          command('s', node.dx1, node.dy1, node.dx2, node.dy2)
        is PathNode.QuadTo -> command('Q', node.x1, node.y1, node.x2, node.y2)
        is PathNode.RelativeQuadTo -> command('q', node.dx1, node.dy1, node.dx2, node.dy2)
        is PathNode.ReflectiveQuadTo -> command('T', node.x, node.y)
        is PathNode.RelativeReflectiveQuadTo -> command('t', node.dx, node.dy)
        is PathNode.ArcTo ->
          command(
            'A',
            node.horizontalEllipseRadius,
            node.verticalEllipseRadius,
            node.theta,
            if (node.isMoreThanHalf) 1f else 0f,
            if (node.isPositiveArc) 1f else 0f,
            node.arcStartX,
            node.arcStartY,
          )
        is PathNode.RelativeArcTo ->
          command(
            'a',
            node.horizontalEllipseRadius,
            node.verticalEllipseRadius,
            node.theta,
            if (node.isMoreThanHalf) 1f else 0f,
            if (node.isPositiveArc) 1f else 0f,
            node.arcStartDx,
            node.arcStartDy,
          )
      }
    }
  }

  private fun StringBuilder.command(letter: Char, vararg values: Float) {
    append(letter)
    values.forEachIndexed { index, value ->
      if (index > 0) append(',')
      append(number(value))
    }
  }

  /**
   * Text that parses back to the same float. Whole numbers drop their `.0`; `-0` keeps its sign,
   * because `PathNode` equality, like every data class's, tells `-0f` from `0f`.
   */
  private fun number(value: Float): String =
    when {
      value == 0f -> if (1f / value < 0f) "-0" else "0"
      value == value.toInt().toFloat() -> value.toInt().toString()
      else -> value.toString()
    }

  /** The inverse of [toPathString], and only of it: one letter per node, comma-separated values. */
  internal fun parsePath(text: String): List<PathNode> {
    if (text.isEmpty()) return emptyList()
    val nodes = ArrayList<PathNode>()
    var index = 0
    while (index < text.length) {
      val letter = text[index++]
      var end = index
      // SVG has no `e`/`E` command, so one here is an exponent (`1.0E-4`), not the next node.
      while (end < text.length && (!text[end].isLetter() || text[end] == 'e' || text[end] == 'E')) {
        end++
      }
      val values =
        if (end == index) FloatArray(0)
        else text.substring(index, end).split(',').map { it.toFloat() }.toFloatArray()
      index = end
      nodes += node(letter, values)
    }
    return nodes
  }

  private fun node(letter: Char, v: FloatArray): PathNode =
    when (letter) {
      'Z' -> PathNode.Close
      'M' -> PathNode.MoveTo(v[0], v[1])
      'm' -> PathNode.RelativeMoveTo(v[0], v[1])
      'L' -> PathNode.LineTo(v[0], v[1])
      'l' -> PathNode.RelativeLineTo(v[0], v[1])
      'H' -> PathNode.HorizontalTo(v[0])
      'h' -> PathNode.RelativeHorizontalTo(v[0])
      'V' -> PathNode.VerticalTo(v[0])
      'v' -> PathNode.RelativeVerticalTo(v[0])
      'C' -> PathNode.CurveTo(v[0], v[1], v[2], v[3], v[4], v[5])
      'c' -> PathNode.RelativeCurveTo(v[0], v[1], v[2], v[3], v[4], v[5])
      'S' -> PathNode.ReflectiveCurveTo(v[0], v[1], v[2], v[3])
      's' -> PathNode.RelativeReflectiveCurveTo(v[0], v[1], v[2], v[3])
      'Q' -> PathNode.QuadTo(v[0], v[1], v[2], v[3])
      'q' -> PathNode.RelativeQuadTo(v[0], v[1], v[2], v[3])
      'T' -> PathNode.ReflectiveQuadTo(v[0], v[1])
      't' -> PathNode.RelativeReflectiveQuadTo(v[0], v[1])
      'A' -> PathNode.ArcTo(v[0], v[1], v[2], v[3] != 0f, v[4] != 0f, v[5], v[6])
      'a' -> PathNode.RelativeArcTo(v[0], v[1], v[2], v[3] != 0f, v[4] != 0f, v[5], v[6])
      else -> error("unknown path command '$letter'")
    }

  private val json = Json {
    encodeDefaults = false
    ignoreUnknownKeys = true
  }

  @Serializable internal data class Shard(val icons: Map<String, IconVector>)

  @Serializable
  internal data class IconVector(
    val name: String,
    val width: Float = 24f,
    val height: Float = 24f,
    val viewportWidth: Float = 24f,
    val viewportHeight: Float = 24f,
    val autoMirror: Boolean = false,
    val children: List<IconNode> = emptyList(),
  )

  /** A path when [children] is null, otherwise a group. */
  @Serializable
  internal data class IconNode(
    val name: String = "",
    val path: String? = null,
    val evenOdd: Boolean = false,
    val fill: Long? = null,
    val fillAlpha: Float = 1f,
    // Stroke defaults are `materialPath`'s, not `VectorPath`'s: every Material path carries a
    // width of 1, a bevel join and a miter of 1 with no stroke to apply them to, and equality
    // compares them, so the common case is the one that costs nothing in the file.
    val stroke: Long? = null,
    val strokeAlpha: Float = 1f,
    val strokeWidth: Float = 1f,
    val strokeCap: Int = 0,
    val strokeJoin: Int = 2,
    val strokeMiter: Float = 1f,
    val rotation: Float = DefaultRotation,
    val pivotX: Float = DefaultPivotX,
    val pivotY: Float = DefaultPivotY,
    val scaleX: Float = DefaultScaleX,
    val scaleY: Float = DefaultScaleY,
    val translationX: Float = DefaultTranslationX,
    val translationY: Float = DefaultTranslationY,
    val clip: String = "",
    val children: List<IconNode>? = null,
  )
}
