package com.h3consultingpartners.ifatccompanion.core.connect

/**
 * Swift's `String.trimmingCharacters(in: .whitespaces)`, which several call sites in
 * this area depend on: it strips spaces, tabs and Unicode space separators but **not**
 * newlines. Kotlin's own [String.trim] also strips `\n`/`\r`, which would change what a
 * multi-line manifest name or flight-plan identifier trims to, so the newline class is
 * excluded explicitly here.
 */
internal fun String.trimmingWhitespaces(): String = trim { ch ->
    ch.isWhitespace() && ch !in NEWLINE_CHARACTERS
}

/** Swift's `CharacterSet.newlines`: LF, VT, FF, CR, NEL and the two Unicode separators. */
private val NEWLINE_CHARACTERS: CharArray =
    intArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)
        .map { it.toChar() }
        .toCharArray()

/** Swift's `String?.nonEmpty` idiom — `""` becomes `null`, so "unknown" and "blank" agree. */
internal fun String.nonEmptyOrNull(): String? = ifEmpty { null }
