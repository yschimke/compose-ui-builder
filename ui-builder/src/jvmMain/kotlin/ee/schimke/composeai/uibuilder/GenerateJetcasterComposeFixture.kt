package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.artwork.GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.artwork.PROJECT_OWNED_ARTWORK_ADAPTER_ID
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object GenerateJetcasterComposeFixture {
  @JvmStatic
  fun main(args: Array<String>) {
    require(args.size == 1) { "expected generated Kotlin output path" }
    val output = File(args.single())
    val loader = GenerateJetcasterComposeFixture::class.java
    val fixture =
      Json.parseToJsonElement(
          checkNotNull(loader.getResource("/jetcaster-discover-operations-v1.json")).readText()
        )
        .jsonObject
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(loader.getResource("/jetcaster-discover-capabilities-v1.json")).readText()
      )
    val result =
      CapabilityComposeCodeExporter.export(
        UiBuilderReducer.replay(fixture).document,
        catalog,
        JETCASTER_ARTWORK_ADAPTER,
      )
    val exported = result.requireSource()
    val fingerprint = exported.sha256()
    val expected = "// Generator content SHA-256: $fingerprint\n$exported"
    output.parentFile.mkdirs()
    output.writeText(expected)
  }

  private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(encodeToByteArray()).joinToString("") {
      it.toUByte().toString(16).padStart(2, '0')
    }

  private val JETCASTER_ARTWORK_ADAPTER =
    ComposeAssetAdapter(
      id = PROJECT_OWNED_ARTWORK_ADAPTER_ID,
      renderer =
        ComposeAssetRenderer(
          symbol = "ProjectOwnedJetcasterArtwork",
          importName = "ee.schimke.composeai.uibuilder.artwork.ProjectOwnedJetcasterArtwork",
        ),
      bindings =
        mapOf(
          ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY to
            ComposeAssetBinding(
              sourceIdentity =
                "project-owned-artwork/v1/$ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY/square-512"
            ),
          GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY to
            ComposeAssetBinding(
              sourceIdentity =
                "project-owned-artwork/v1/$GOOGLE_DEVELOPERS_PODCAST_ARTWORK_KEY/square-512"
            ),
        ),
    )
}
