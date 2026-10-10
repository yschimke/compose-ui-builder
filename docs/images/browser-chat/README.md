# Browser chat evidence

The [prior agent prompt panel](../agent-prompt/after.png) is the baseline before browser chat.

The current browser chat contents are captured with a fake, connected OpenRouter host at 440 px
and 320 px. No provider request or credential is used. These are Compose UI-test captures of the
actual common UI; the dialog chrome and live browser OAuth/network flow are not pictured.

| Width | Browser chat | External handoff |
| --- | --- | --- |
| Desktop (440 px) | [Chat](desktop-chat.png) | [Handoff](desktop-handoff.png) |
| Mobile (320 px) | [Chat](mobile-chat.png) | [Handoff](mobile-handoff.png) |

`BrowserChatContentsTest` regenerates the captures under `ui-builder/build/browser-chat`. The UI
test also exercises entering a key without remembering it, sending a turn, Stop and Disconnect.
