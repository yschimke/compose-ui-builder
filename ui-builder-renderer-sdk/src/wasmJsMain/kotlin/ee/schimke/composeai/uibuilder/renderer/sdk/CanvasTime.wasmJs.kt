package ee.schimke.composeai.uibuilder.renderer.sdk

internal actual fun localUtcOffsetSeconds(epochMillis: Long): Int =
  // `getTimezoneOffset` is minutes *behind* UTC: -120 in a zone two hours ahead.
  -timezoneOffsetMinutes(epochMillis.toDouble()) * 60

private fun timezoneOffsetMinutes(epochMillis: Double): Int =
  js("new Date(epochMillis).getTimezoneOffset()")
