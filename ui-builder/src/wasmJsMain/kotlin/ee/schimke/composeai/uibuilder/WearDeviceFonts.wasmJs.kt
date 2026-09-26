package ee.schimke.composeai.uibuilder

/** Nothing to read synchronously in a browser: its hosts call [registerWearDeviceFonts] first. */
internal actual fun ensureBundledWearDeviceFonts() = Unit
