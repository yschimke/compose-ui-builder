#!/usr/bin/env bash
set -euo pipefail
source_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
scratch_root=$(mktemp -d "${TMPDIR:-/tmp}/ui-builder-production-consumer.XXXXXX")
trap 'rm -rf "${scratch_root}"' EXIT
repository="${scratch_root}/repository"
consumer="${scratch_root}/consumer"
version="0.0.0-generation-gate-SNAPSHOT"
mkdir -p "${repository}" "${consumer}/gradle/wrapper" "${consumer}/docs/design/fixtures/ui-builder"
# Stage only the build tool and its export dependency; never publish remotely or require a host.
PLUGIN_VERSION="${version}" "${source_root}/gradlew" --max-workers=2 \
  --init-script "${source_root}/scripts/ui-builder-external-consumer/publish.init.gradle" \
  -PuiBuilderExtractionRepository="${repository}" \
  :ui-builder-export:publishAllPublicationsToUiBuilderExtractionRepository \
  :ui-builder-codegen-jvm:publishAllPublicationsToUiBuilderExtractionRepository
cp -R "${source_root}/ui-builder-production-consumer" "${consumer}/ui-builder-production-consumer"
rm -rf "${consumer}/ui-builder-production-consumer/build"
cp "${source_root}/gradlew" "${consumer}/gradlew"
cp "${source_root}/gradle/wrapper/gradle-wrapper.jar" "${source_root}/gradle/wrapper/gradle-wrapper.properties" "${consumer}/gradle/wrapper/"
cp "${source_root}/gradle/libs.versions.toml" "${consumer}/gradle/"
cp "${source_root}/docs/design/fixtures/ui-builder/compose-foundation-components-v1.json" \
  "${source_root}/docs/design/fixtures/ui-builder/m3-catalog-components-v1.json" "${consumer}/docs/design/fixtures/ui-builder/"
cat > "${consumer}/settings.gradle.kts" <<'SETTINGS'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral(); google() } }
dependencyResolutionManagement {
  repositories {
    maven { url = uri(providers.gradleProperty("gateRepository").get()) }
    mavenCentral()
    google()
  }
}
rootProject.name = "external-production-consumer"
include(":ui-builder-production-consumer")
SETTINGS
# Rendering is verified in the producer fixture; this independent build proves publication,
# generation and compilation without resolving producer projects or source paths.
python3 - "${consumer}/ui-builder-production-consumer/build.gradle.kts" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);p.write_text(p.read_text().replace('  alias(libs.plugins.compose.preview)\n',''))
PY
git -C "${consumer}" init -q
git -C "${consumer}" add ui-builder-production-consumer/src/main/ui
(
  cd "${consumer}"
  # Resolve published POM/BOM dependencies before testing offline regeneration. The producer
  # can use Gradle module metadata without populating the consumer's Maven POM cache.
  ./gradlew --no-daemon --max-workers=2 \
    -PgateRepository="${repository}" -PuiBuilderGeneratorVersion="${version}" \
    :ui-builder-production-consumer:compileKotlin
  # Rebuild after deleting output and verify byte-for-byte reproducibility across builds.
  cp -R ui-builder-production-consumer/build/generated/uiBuilder first-generation
  rm -rf ui-builder-production-consumer/build/generated/uiBuilder
  ./gradlew --offline --no-daemon --max-workers=2 \
    -PgateRepository="${repository}" -PuiBuilderGeneratorVersion="${version}" \
    :ui-builder-production-consumer:compileKotlin
  diff -r first-generation ui-builder-production-consumer/build/generated/uiBuilder
  # A mapped project type is checked by the real Kotlin compiler, not accepted as a dictionary.
  cp ui-builder-production-consumer/src/main/kotlin/example/domain/ProjectEpisode.kt external-model-original.kt
  python3 - ui-builder-production-consumer/src/main/kotlin/example/domain/ProjectEpisode.kt <<'PYTHON'
import pathlib,sys
p=pathlib.Path(sys.argv[1]);p.write_text(p.read_text().replace('displayTitle', 'renamedTitle'))
PYTHON
  if ./gradlew --offline --no-daemon --max-workers=2 \
    -PgateRepository="${repository}" -PuiBuilderGeneratorVersion="${version}" \
    :ui-builder-production-consumer:compileKotlin >external-mapping-build.log 2>&1; then
    echo "ERROR: incompatible external model was accepted" >&2
    exit 1
  fi
  grep -q 'Unresolved reference.*displayTitle' external-mapping-build.log
  cp external-model-original.kt ui-builder-production-consumer/src/main/kotlin/example/domain/ProjectEpisode.kt
  # Untracking an unchanged import must fail even when generated Kotlin is already up to date.
  git rm --cached -q ui-builder-production-consumer/src/main/ui/components/EpisodeCard.uid
  if ./gradlew --offline --no-daemon --max-workers=2 \
    -PgateRepository="${repository}" -PuiBuilderGeneratorVersion="${version}" \
    :ui-builder-production-consumer:compileKotlin >untracked-build.log 2>&1; then
    echo "ERROR: untracked component was accepted" >&2
    exit 1
  fi
  grep -q 'UNTRACKED_INPUT' untracked-build.log
)
# A different absolute checkout location must generate the same source bytes.
if test -d "${source_root}/ui-builder-production-consumer/build/generated/uiBuilder"; then
  diff -r "${source_root}/ui-builder-production-consumer/build/generated/uiBuilder" \
    "${consumer}/ui-builder-production-consumer/build/generated/uiBuilder"
fi
printf '%s\n' 'Published durable-generation consumer gate passed'
