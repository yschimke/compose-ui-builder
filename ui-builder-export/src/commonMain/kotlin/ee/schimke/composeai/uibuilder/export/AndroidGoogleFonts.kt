package ee.schimke.composeai.uibuilder.export

/**
 * Generated Android code that sets a theme's [ThemeTypefaces] as Google Fonts families:
 * `FontFamily(Font(GoogleFont("Michroma"), GoogleFontsProvider, …))` on each role of the theme's
 * typography.
 *
 * A downloadable font needs a provider, and the provider needs Google Play services' signing
 * certificates. Android Studio's wizard writes those into the app as `res/values/font_certs.xml`,
 * which a generated screen cannot rely on — its module may have none, and the native lane compiles
 * it against a catalog bundle that has none. So the generated file declares the provider itself,
 * with the two published certificates inlined (Play services' development and production keys, as
 * Android's downloadable-fonts sample ships them). A design that names no typeface writes none of
 * this.
 *
 * One instance per generated file: it collects the families [typography] is asked for, and the file
 * writes [declarations] once below the screen and [imports] with the rest.
 */
class AndroidGoogleFonts(private val indent: String = "    ") {
  /** Family name to the identifier of its generated `FontFamily` val, in first-use order. */
  private val families = linkedMapOf<String, String>()

  val isEmpty: Boolean
    get() = families.isEmpty()

  /**
   * The lines of a `typography = …` argument: [receiver]'s typography (`MaterialTheme.typography`)
   * with each role in [roles] re-pointed at its family, at [depth] levels of indent.
   */
  fun typography(receiver: String, roles: Map<String, String>, depth: Int): List<String> {
    val pad = indent.repeat(depth)
    return listOf("${pad}typography =", "$pad$indent$receiver.run {", "$pad$indent${indent}copy(") +
      roles.map { (role, family) ->
        "$pad$indent$indent$indent$role = $role.copy(fontFamily = ${identifier(family)}),"
      } +
      listOf("$pad$indent$indent)", "$pad$indent},")
  }

  private fun identifier(family: String): String {
    val name = ThemeTypefaces.familyName(family)
    return families.getOrPut(name) {
      val base =
        name
          .split(Regex("[^A-Za-z0-9]+"))
          .filter { it.isNotEmpty() }
          .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
      val stem = if (base.firstOrNull()?.isLetter() == true) base else "Font$base"
      var candidate = "${stem}FontFamily"
      var n = 2
      while (candidate in families.values) candidate = "${stem}FontFamily${n++}"
      candidate
    }
  }

  val imports: List<String>
    get() =
      if (isEmpty) emptyList()
      else
        listOf(
          "android.util.Base64",
          "androidx.compose.ui.text.font.FontFamily",
          "androidx.compose.ui.text.font.FontWeight",
          "androidx.compose.ui.text.googlefonts.Font",
          "androidx.compose.ui.text.googlefonts.GoogleFont",
        )

  /**
   * The provider and one `FontFamily` per family, as top-level declarations. Each family carries
   * the weights a type scale uses — regular, medium and bold — and the provider fetches each from
   * Google Fonts on first use; until it arrives the role draws in the platform face.
   */
  val declarations: List<String>
    get() {
      if (isEmpty) return emptyList()
      val i = indent
      return families.map { (name, identifier) ->
        val google = "GoogleFont(${name.quoted()})"
        buildString {
          appendLine("private val $identifier =")
          appendLine("${i}FontFamily(")
          listOf("Normal", "Medium", "Bold").forEach {
            appendLine("$i${i}Font($google, GoogleFontsProvider, FontWeight.$it),")
          }
          append("$i)")
        }
      } +
        buildString {
          appendLine(
            "// Google Play services' published font-provider certificates, inlined so this file"
          )
          appendLine("// compiles without a `font_certs.xml` of its own.")
          appendLine("private val GoogleFontsProvider =")
          appendLine("${i}GoogleFont.Provider(")
          appendLine("$i${i}providerAuthority = \"com.google.android.gms.fonts\",")
          appendLine("$i${i}providerPackage = \"com.google.android.gms\",")
          appendLine("$i${i}certificates =")
          appendLine("$i$i${i}listOf(")
          GMS_FONTS_CERTIFICATES.forEach { chunks ->
            appendLine("$i$i$i${i}listOf(")
            appendLine("$i$i$i$i${i}Base64.decode(")
            chunks.forEachIndexed { index, chunk ->
              val joiner = if (index == chunks.lastIndex) "," else " +"
              appendLine("$i$i$i$i$i$i\"$chunk\"$joiner")
            }
            appendLine("$i$i$i$i$i${i}Base64.DEFAULT,")
            appendLine("$i$i$i$i$i),")
            appendLine("$i$i$i$i),")
          }
          appendLine("$i$i$i),")
          append("$i)")
        }
    }

  private companion object {
    /** Play services' development and production font-provider certificates, base64. */
    val GMS_FONTS_CERTIFICATES: List<List<String>> =
      listOf(
        listOf(
          "MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEG",
          "A1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQ",
          "MA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBh",
          "bmRyb2lkLmNvbTAeFw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGUMQswCQYDVQQGEwJVUzET",
          "MBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9p",
          "ZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9p",
          "ZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANbOLggKv+IxTdGNs8/T",
          "GFy0PTP6DHThvbbR24kT9ixcOd9W+EaBPWW+wPPKQmsHxajtWjmQwWfna8mZuSeJS48LIgAZlKkpFeVy",
          "xW0qMBujb8X8ETrWy550NaFtI6t9+u7hZeTfHwqNvacKhp1RbE6dBRGWynwMVX8XW8N1+UjFaq6GCJuk",
          "T4qmpN2afb8sCjUigq0GuMwYXrFVee74bQgLHWGJwPmvmLHC69EH6kWr22ijx4OKXlSIx2xT1AsSHee7",
          "0w5iDBiK4aph27yH3TxkXy9V89TDdexAcKk/cVHYNnDBapcavl7y0RiQ4biu8ymM8Ga/nmzhRKya6G0c",
          "Gw8CAQOjgfwwgfkwHQYDVR0OBBYEFI0cxb6VTEM8YYY6FbBMvAPyT+CyMIHJBgNVHSMEgcEwgb6AFI0c",
          "xb6VTEM8YYY6FbBMvAPyT+CyoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5p",
          "YTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9p",
          "ZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJANWF",
          "uGx90071MAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBABnTDPEF+3iSP0wNfdIjIz1AlnrP",
          "zgAIHVvXxunW7SBrDhEglQZBbKJEk5kT0mtKoOD1JMrSu1xuTKEBahWRbqHsXclaXjoBADb0kkjVEJu/",
          "Lh5hgYZnOjvlba8Ld7HCKePCVePoTJBdI4fvugnL8TsgK05aIskyY0hKI9L8KfqfGTl1lzOv2KoWD0KW",
          "wtAWPoGChZxmQ+nBli+gwYMzM1vAkP+aayLe0a1EQimlOalO762r0GXO0ks+UeXde2Z4e+8S/pf7pITE",
          "I/tP+MxJTALw9QUWEv9lKTk+jkbqxbsh8nfBUapfKqYn0eidpwq2AzVp3juYl7//fKnaPhJD9gs=",
        ),
        listOf(
          "MIIEQzCCAyugAwIBAgIJAMLgh0ZkSjCNMA0GCSqGSIb3DQEBBAUAMHQxCzAJBgNVBAYTAlVTMRMwEQYD",
          "VQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5j",
          "LjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDAeFw0wODA4MjEyMzEzMzRaFw0zNjAx",
          "MDcyMzEzMzRaMHQxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1Nb3Vu",
          "dGFpbiBWaWV3MRQwEgYDVQQKEwtHb29nbGUgSW5jLjEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMH",
          "QW5kcm9pZDCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW",
          "0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5Y",
          "ZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfY",
          "wXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OG",
          "azvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k//0zOHKrUiGYXtqw/A0LFFtqoZKFjnkCAQOj",
          "gdkwgdYwHQYDVR0OBBYEFMd9jMIhF1Ylmn/Tgt9r45jk14alMIGmBgNVHSMEgZ4wgZuAFMd9jMIhF1Yl",
          "mn/Tgt9r45jk14aloXikdjB0MQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UE",
          "BxMNTW91bnRhaW4gVmlldzEUMBIGA1UEChMLR29vZ2xlIEluYy4xEDAOBgNVBAsTB0FuZHJvaWQxEDAO",
          "BgNVBAMTB0FuZHJvaWSCCQDC4IdGZEowjTAMBgNVHRMEBTADAQH/MA0GCSqGSIb3DQEBBAUAA4IBAQBt",
          "0lLO74UwLDYKqs6Tm8/yzKkEu116FmH4rkaymUIE0P9KaMftGlMexFlaYjzmB2OxZyl6euNXEsQH8gjw",
          "yxCUKRJNexBiGcCEyj6z+a1fuHHvkiaai+KL8W1EyNmgjmyy8AW7P+LLlkR+ho5zEHatRbM/YAnqGcFh",
          "5iZBqpknHf1SKMXFh4dd239FJ1jWYfbMDMy3NS5CTMQ2XFI1MvcyUTdZPErjQfTbQe3aDQsQcafEQPD+",
          "nqActifKZ0Np0IS9L9kR/wbNvyz6ENwPiTrjV2KRkEjH78ZMcUQXg0L3BYHJ3lc69Vs5Ddf9uUGGMYld",
          "X3WfMBEmh/9iFBDAaTCK",
        ),
      )
  }
}
