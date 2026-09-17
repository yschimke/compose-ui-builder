#!/usr/bin/env bash
set -euo pipefail

source_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
fixture_root="${source_root}/scripts/ui-builder-external-consumer"
scratch_root=$(mktemp -d "${TMPDIR:-/tmp}/compose-preview-ui-builder-consumer.XXXXXX")
trap 'rm -rf "${scratch_root}"' EXIT

published_repository="${scratch_root}/repository"
consumer_root="${scratch_root}/consumer"
gate_version="0.0.0-extraction-gate-SNAPSHOT"
mkdir -p "${published_repository}" "${consumer_root}"

# The modules to stage are DERIVED, not listed. This gate used to name three of them here while
# `release.yml` named five somewhere else, and the two lists disagreed for six releases: this script
# staged `:ui-builder-render-bundle` (so the gate passed) while the release never published it (so
# every consumer of 3.3.0 through 3.8.0 got a POM naming an artifact that was not on Maven Central).
# `printPublishedProjectPaths` is now the only place the set is decided, and the release job reads
# the same one through `publishReleaseArtifacts`. Staging the WHOLE release set rather than the
# subset the fixture resolves is deliberate: it also proves every module the release will publish
# can actually be published, which is the half no gate covered.
#
# `publishAllPublications…` rather than `publishMavenPublication…` because the set now spans both
# plain-JVM modules (one `maven` publication) and multiplatform ones (a root publication plus a
# per-target one); naming a single publication would silently stage half of a KMP module.
# Filtered to project paths rather than taken whole: `--quiet` keeps Gradle's own chatter off
# stdout, but a JVM launched with `JAVA_TOOL_OPTIONS` prints a banner that some environments put
# there, and one stray line would become an unresolvable task name.
mapfile -t published_projects < <(
  "${source_root}/gradlew" --no-daemon --quiet printPublishedProjectPaths | grep '^:'
)
test "${#published_projects[@]}" -gt 0 || {
  echo "printPublishedProjectPaths returned no modules - the release would publish nothing" >&2
  exit 1
}

publish_tasks=()
for project_path in "${published_projects[@]}"; do
  publish_tasks+=("${project_path}:publishAllPublicationsToUiBuilderExtractionRepository")
done

PLUGIN_VERSION="${gate_version}" \
  "${source_root}/gradlew" \
  --no-daemon \
  --init-script "${fixture_root}/publish.init.gradle" \
  -PuiBuilderExtractionRepository="${published_repository}" \
  "${publish_tasks[@]}"

cp -R "${fixture_root}/." "${consumer_root}/"
rm "${consumer_root}/publish.init.gradle"
cp "${source_root}/gradlew" "${consumer_root}/gradlew"
cp "${source_root}/gradlew.bat" "${consumer_root}/gradlew.bat"
mkdir -p "${consumer_root}/gradle/wrapper"
cp "${source_root}/gradle/wrapper/gradle-wrapper.jar" "${consumer_root}/gradle/wrapper/"
cp "${source_root}/gradle/wrapper/gradle-wrapper.properties" "${consumer_root}/gradle/wrapper/"

(
  cd "${consumer_root}"
  ./gradlew \
    --no-daemon \
    --stacktrace \
    -PgateRepository="${published_repository}" \
    -PgateVersion="${gate_version}" \
    -PforbiddenSourceRoot="${source_root}" \
    clean verifyExtractionConsumer
)

echo "UI-builder external consumer gate passed"
