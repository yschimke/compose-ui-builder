#!/bin/sh
# Drives a running desktop app on an Xvfb display with java.awt.Robot: click, wait, key, shot.
# See docs/AGENT_TESTING.md. Usage: drive.sh click 66 57 wait 1500 shot out.png
# Env: DISPLAY (default :99), XAUTHORITY (default: the first /tmp/xvfb-run.*/Xauthority),
#      JAVA_HOME (default /usr/lib/jvm/java-21-openjdk-amd64 — a system JDK, not the Nix one).
set -eu
here=$(cd "$(dirname "$0")" && pwd)
jdk=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
[ -f "$here/Act.class" ] || "$jdk/bin/javac" -d "$here" "$here/Act.java"
DISPLAY=${DISPLAY:-:99} \
XAUTHORITY=${XAUTHORITY:-$(ls /tmp/xvfb-run.*/Xauthority 2>/dev/null | head -1)} \
  "$jdk/bin/java" -cp "$here" Act "$@"
