package ee.schimke.composeai.uibuilder.artwork

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import ee.schimke.composeai.uibuilder.artwork.generated.resources.Res
import ee.schimke.composeai.uibuilder.artwork.generated.resources.jetcaster_owned_android_512
import ee.schimke.composeai.uibuilder.artwork.generated.resources.jetcaster_owned_google_512
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

const val ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY = "jetcaster.cover.android-developers-backstage"
const val GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY = "jetcaster.cover.google-developers-podcast"
const val PROJECT_OWNED_ARTWORK_ADAPTER_ID = "compose-preview-project-owned-jetcaster-artwork/v1"

private const val AndroidFile = "artwork/jetcaster-owned-android-512.png"
private const val GoogleFile = "artwork/jetcaster-owned-google-512.png"

fun projectOwnedJetcasterArtworkFile(assetKey: String): String =
  when (assetKey) {
    ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY -> AndroidFile
    GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY -> GoogleFile
    else -> error("no project-owned Jetcaster artwork for '$assetKey'")
  }

suspend fun readProjectOwnedJetcasterArtwork(assetKey: String): ByteArray =
  Res.readBytes("files/${projectOwnedJetcasterArtworkFile(assetKey)}")

private fun projectOwnedJetcasterArtworkResource(assetKey: String): DrawableResource =
  when (assetKey) {
    ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY -> Res.drawable.jetcaster_owned_android_512
    GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY -> Res.drawable.jetcaster_owned_google_512
    else -> error("no project-owned Jetcaster artwork for '$assetKey'")
  }

@Composable
fun ProjectOwnedJetcasterArtwork(
  assetKey: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop,
) {
  Image(
    painter = painterResource(projectOwnedJetcasterArtworkResource(assetKey)),
    contentDescription = contentDescription,
    modifier = modifier,
    contentScale = contentScale,
  )
}
