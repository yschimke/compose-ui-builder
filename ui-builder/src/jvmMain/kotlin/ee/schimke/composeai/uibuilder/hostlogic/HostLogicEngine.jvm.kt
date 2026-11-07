package ee.schimke.composeai.uibuilder.hostlogic

/**
 * None yet: the desktop app and the IntelliJ plugin show a design's host logic without running it.
 * The engine is QuickJS on Wasm, and running that on the JVM means a Wasm runtime there too.
 */
internal actual val platformHostLogicEngine: HostLogicEngine? = null
