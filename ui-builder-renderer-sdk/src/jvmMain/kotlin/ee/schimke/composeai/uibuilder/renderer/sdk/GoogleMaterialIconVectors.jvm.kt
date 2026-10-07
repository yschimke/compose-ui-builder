package ee.schimke.composeai.uibuilder.renderer.sdk

import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

/** The compiled `material-icons-extended` builders: every key answers at once. */
actual object GoogleMaterialIconVectors {
  actual fun cached(key: String): ImageVector? = generatedGoogleMaterialIconImageVector(key)

  actual suspend fun load(key: String): ImageVector? = cached(key)
}

/** The icon's vector, from the compiled builders. JVM only: the browser loads it as data. */
val GoogleMaterialIcon.imageVector: ImageVector
  get() =
    checkNotNull(GoogleMaterialIconVectors.cached(key)) {
      "Generated Material icon '$key' has no ImageVector binding"
    }

/**
 * Writes the browser's icon files from the compiled builders: [MaterialIconData.shardCount] files
 * named by [MaterialIconData.shardFileName], into the directory given as the only argument.
 */
object MaterialIconDataGenerator {
  @JvmStatic
  fun main(args: Array<String>) {
    val output = File(args.single())
    output.deleteRecursively()
    output.mkdirs()
    GoogleMaterialIcons.chunked(MaterialIconData.SHARD_SIZE).forEachIndexed { shard, icons ->
      check(icons.all { MaterialIconData.shardOf(it.key) == shard })
      output
        .resolve(MaterialIconData.shardFileName(shard))
        .writeText(MaterialIconData.encodeShard(icons.associate { it.key to it.imageVector }))
    }
  }
}
