// The variable fonts `FlexpressVariableFontSources.fromClasspath()` generates outlines from, as a
// jar of their own that ships as a GitHub release asset
// (`compose-preview-ui-builder-export-fonts-<v>.jar`), not on Maven Central.
//
// They used to be resources of `compose-preview-ui-builder-export-jvm`, which made that jar ~5 MB
// instead of ~1 MB, re-uploaded to Central on every release that touched the export. The export
// only reads them through `getResourceAsStream`, so a host that generates variable-font text
// (compose-preview-server) puts this jar on its classpath beside the export and nothing else
// changes; a consumer without it gets the generator's ordinary "could not be read" refusal.
//
// Not `composeai.maven-publishing`, deliberately, like `:ui-builder-web` and
// `:ui-builder-render-bundle`.
plugins { `java-library` }

group = "ee.schimke.composeai"

// The same derivation `:ui-builder-web` uses: `PLUGIN_VERSION` in CI, a patch-bumped SNAPSHOT off
// `.release-please-manifest.json` locally. It names the release asset.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview-" + project.name) }

// Resources only, so loadable on every server JVM.
java { targetCompatibility = JavaVersion.toVersion(libs.versions.java.server.get()) }

// The resource path is the contract: `FlexpressVariableFontSources.CLASSPATH_FONTS`.
// `FlexpressVariableFontSourcesTest` (in `:ui-builder-export`, which has this on its test
// classpath) fails if one is missing.
val stageFlexpressFonts =
  tasks.register<Sync>("stageFlexpressFonts") {
    from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
      include(
        "RobotoFlex.ttf",
        "RobotoFlex-OFL.txt",
        "google-sans-flex-variable.ttf",
        "GoogleSansFlex-OFL.txt",
      )
      into("ee/schimke/composeai/uibuilder/export/fonts")
    }
    into(layout.buildDirectory.dir("generated/flexpressFonts"))
  }

sourceSets { main { resources.srcDir(stageFlexpressFonts) } }
