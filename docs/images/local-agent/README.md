# Local agent UI evidence

The [previous desktop editor](../../AGENT_TESTING.md#1-desktop-jvm-app-under-xvfb) had no local agent
chat; the [web external handoff panel](../browser-chat/desktop-handoff.png) is a comparison with the
existing web agent surface.

These captures show the actual common local-agent panel at 440 px and 320 px, with a fake host
reporting both harnesses installed. They omit dialog chrome and do not exercise real CLI login or
paid inference. `LocalAgentContentsTest` regenerates them under `ui-builder/build/local-agent`.

| Desktop | Compact |
| --- | --- |
| [440 px](desktop.png) | [320 px](compact.png) |
