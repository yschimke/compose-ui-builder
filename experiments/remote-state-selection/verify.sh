#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Regenerate every source even if a previous filtered test left the task up to date.
./gradlew :ui-builder-export:jvmTest --tests '*RemoteStateSelectionExportTest' --tests '*RemoteRootSourceExportTest' --rerun-tasks
python3 experiments/remote-state-selection/generate-repetition.py
./gradlew -p experiments/remote-state-selection testDebugUnitTest
./gradlew -I experiments/remote-state-selection/desktop-proof.init.gradle :ui-builder:jvmTest --tests '*RepetitionComposeProofTest'
