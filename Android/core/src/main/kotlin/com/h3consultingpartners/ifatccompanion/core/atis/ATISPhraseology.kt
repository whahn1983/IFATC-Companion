package com.h3consultingpartners.ifatccompanion.core.atis

import com.h3consultingpartners.ifatccompanion.core.phraseology.Phonetic

/**
 * Deterministic rendering of raw D-ATIS text into forms the app can show and speak.
 *
 * The raw D-ATIS text (from the FAA feed) is a mostly plain-language message with an
 * **embedded coded observation** — the same groups you see in a METAR: wind
 * (`25012KT`), visibility (`10SM`), sky cover (`FEW015 OVC250`), weather (`-RA BR`),
 * temperature/dewpoint (`07/M02`) and altimeter (`A2992`) — followed by the runways,
 * approaches and NOTAMs in abbreviated English (`ILS RWY 24R APCH IN USE`, `DEPG RWY
 * 25R`, `TWY B CLSD`).
 *
 * For the transcript we keep the text essentially verbatim; for text-to-speech we
 * decode every coded group into the way a real ATIS voice reads it on the air ("wind
 * two five zero at one two", "visibility one zero", "few clouds at one thousand five
 * hundred", "temperature seven, dewpoint minus two", "altimeter two niner niner two"),
 * expand the common abbreviations, and speak any remaining digit run one digit at a
 * time. No AI, no invented content: every transform is a fixed rule applied to the
 * published text.
 *
 * Ported from `IFATCCompanion/ATIS/ATISPhraseology.swift`. `NSRegularExpression` becomes
 * [Regex]; every pattern is compiled once at class-init rather than per call, because
 * the abbreviation table alone is ~180 patterns and this runs on every ATIS broadcast.
 */
object ATISPhraseology {

    /**
     * The phonetic word for an information code letter, e.g. "A" -> "Alpha". Used to
     * build the "…information Alpha" the pilot appends to ATC calls.
     */
    fun phoneticLetter(letter: String): String {
        val t = letter.uppercase().trim()
        val ch = t.firstOrNull() ?: return t
        return Phonetic.letterWords[ch] ?: t
    }

    /**
     * A cleaned, human-readable version of the raw D-ATIS text for the transcript
     * (whitespace collapsed; abbreviations left intact — pilots read them fine).
     */
    fun displayText(raw: String): String = collapseWhitespace(raw).trim()

    /**
     * A TTS-friendly rendering: the embedded coded observation decoded to spoken
     * phraseology, abbreviations expanded, and every remaining digit run spoken as
     * individual digits per the selected phraseology pack ("niner", "tree/fower/fife"
     * under ICAO).
     */
    fun spokenText(raw: String, icao: Boolean = false): String {
        var s = " " + collapseWhitespace(raw).uppercase() + " "

        // Strip the spelled-out readback the FAA appends after the altimeter, e.g.
        // "A2992 (TWO NINER NINER TWO)" — the coded group is decoded below, so the
        // parenthetical would otherwise be spoken twice. Only parentheticals made up
        // entirely of number words are removed; "(GPS)", "(CLSD)" etc. are preserved.
        s = PARENTHETICAL.replaceEach(s) { g ->
            if (isNumberReadback(g[1])) " " else "(" + g[1] + ")"
        }

        // Drop the coded METAR remarks group ("RMK AO2 SLP224 T00331122 …") up to the
        // end of its sentence — it is dense station coding no ATIS voice reads aloud.
        s = REMARKS.replaceEach(s) { " " }

        // Information code letter → phonetic word: "INFO S" / "INFORMATION S" ->
        // "information Sierra" (header and the closing "…ADVS YOU HAVE INFO S").
        s = INFO_LETTER.replaceEach(s) { g -> "information " + phoneticLetter(g[1]) }

        // Zulu observation time. Two published forms: "2352Z" (HHMMZ) and the day-stamped
        // "042252" (DDHHMM, no Z). For the day-stamped form only the time is spoken.
        s = ZULU.replaceEach(s) { g -> Phonetic.spellDigits(g[1], icao) + " zulu" }
        s = DAY_STAMP.replaceEach(s) { g ->
            val hhmm = dayStampedTime(g[1]) ?: return@replaceEach g[1]
            Phonetic.spellDigits(hhmm, icao) + " zulu"
        }

        // Altimeter (inHg): "A2992" -> "altimeter two niner niner two".
        s = ALTIMETER.replaceEach(s) { g -> "altimeter " + Phonetic.spellDigits(g[1], icao) }
        // QNH (hectopascals): "Q1013" -> "Q N H one zero one three".
        s = QNH.replaceEach(s) { g -> "Q N H " + Phonetic.spellDigits(g[1], icao) }

        // Wind: "00000KT" (calm), "VRB05KT" (variable), "25012KT", "25012G30KT", and
        // the metric "…MPS" forms.
        s = WIND.replaceEach(s) { g -> spokenWind(body = g[1], gust = g[2], unit = g[3], icao = icao) }
        // Variable wind-direction range: "210V280" -> "variable between two one zero and
        // two eight zero".
        s = WIND_VARIABLE.replaceEach(s) { g ->
            "variable between " + Phonetic.spellDigits(g[1], icao) +
                " and " + Phonetic.spellDigits(g[2], icao)
        }

        // Visibility (statute miles): greater-than, less-than, mixed fraction, fraction,
        // then whole. Order matters — the more specific fraction forms first.
        s = VIS_MORE.replaceEach(s) { g -> "visibility more than " + Phonetic.spellDigits(g[1], icao) }
        s = VIS_LESS_FRACTION.replaceEach(s) { g -> "visibility less than " + spokenFraction(g[1], g[2]) }
        s = VIS_MIXED_FRACTION.replaceEach(s) { g ->
            "visibility " + Phonetic.spellDigits(g[1], icao) + " and " + spokenFraction(g[2], g[3])
        }
        s = VIS_FRACTION.replaceEach(s) { g -> "visibility " + spokenFraction(g[1], g[2]) }
        s = VIS_WHOLE.replaceEach(s) { g -> "visibility " + Phonetic.spellDigits(g[1], icao) }

        // RVR: "R28L/2400FT", "R06/2000V3000FT", "R28L/P6000FT", "R28L/M0600FT".
        s = RVR.replaceEach(s) { g ->
            spokenRVR(runway = g[1], p1 = g[2], v1 = g[3], p2 = g[4], v2 = g[5], icao = icao)
        }

        // Temperature / dewpoint: "07/M02", "19/13", "M05/M10", "04/-09" — the negative
        // sign appears as either the METAR "M" prefix or a literal minus in real SWIM text.
        s = TEMP_DEWPOINT.replaceEach(s) { g ->
            "temperature " + spokenTemp(g[1], icao) + ", dewpoint " + spokenTemp(g[2], icao)
        }

        // Sky cover with cloud base: "FEW015" -> "few clouds at one thousand five
        // hundred", "OVC008" -> "eight hundred overcast", "VV002" -> "indefinite ceiling
        // two hundred". Optional CB / TCU cloud type is spoken too.
        s = CLOUD.replaceEach(s) { g -> spokenCloud(cover = g[1], hundreds = g[2], type = g[3], icao = icao) }

        // Weather phenomena groups: "-RA", "+TSRA", "VCSH", "FZFG", "BR". Bounded by
        // delimiters and matched only against the real METAR weather codes, so plain
        // words ("RWY", "INFO", "GROUND") are never mistaken for weather.
        s = WEATHER_GROUP.replaceEach(s) { g -> decodeWeather(g[1]) ?: g[1] }

        // Frequencies embedded in the text: "127.05" -> "one two seven point zero five".
        s = FREQUENCY.replaceEach(s) { g ->
            val separator = if (icao) "decimal" else "point"
            Phonetic.spellDigits(g[1], icao) + " " + separator + " " + Phonetic.spellDigits(g[2], icao)
        }

        // Runway keyword written flush against its designator ("RY8R", "RWY22L") — some
        // feeds publish it with no space. There is no word boundary inside the token, so
        // neither the designator rule below nor the boundary-anchored abbreviation pass can
        // see it: the keyword would survive to be voiced letter by letter around the digits
        // ("R Y eight R"). Split the keyword off its designator here — after the coded
        // groups, which carry their own runway forms (RVR) and would be broken by an
        // earlier split.
        s = FLUSH_RUNWAY_KEYWORD.replaceEach(s) { g -> g[1] + " " + g[2] }

        // Runway designators: "24R" -> "two four right", "25L" -> "two five left" (before
        // the generic digit rule, which would otherwise leave a bare "R").
        s = RUNWAY_DESIGNATOR.replaceEach(s) { g ->
            val side = RUNWAY_SIDES[g[2]] ?: g[2]
            Phonetic.spellDigits(g[1], icao) + " " + side
        }

        // Approach-variant single letters → phonetic words: "RNAV Z" -> "RNAV Zulu",
        // "ILS Z RWY 4L" -> "ILS Zulu runway…". Scoped to these keywords so a stray
        // compass "N"/"S" is never turned into a phonetic word. The keyword is kept for
        // the abbreviation pass below to expand.
        s = APPROACH_VARIANT.replaceEach(s) { g -> g[1] + " " + phoneticLetter(g[2]) }

        // Taxiway identifiers → phonetic words. A taxiway ident is one or two letters with
        // an optional trailing number ("B", "SB", "B4"), so a multi-letter ident is spelled
        // phonetically ("TWY SB" -> "taxiway Sierra Bravo", "TWY B4" -> "taxiway Bravo
        // four") rather than left as bare letters the synthesizer reads as "S B". The
        // one/two-letter bound keeps a following abbreviation word (e.g. "CLSD") from being
        // swallowed. A two-letter, digit-less token that is a common word / abbreviation
        // (e.g. "TWYS IN USE", "TWY SW OF …") is left alone for the word/abbreviation
        // passes below. Some feeds put a comma right after the keyword ("TWY, S") — the
        // optional comma is matched (and dropped) so the ident still spells phonetically
        // instead of being left as a bare "S". The keyword is kept for the abbreviation
        // pass to expand.
        s = TAXIWAY_KEYWORD.replaceEach(s) { g ->
            val ident = g[2]
            if (ident.length == 2 && ident.all { it.isLetter() } && ident in nonTaxiwayTokens) {
                g[1] + " " + ident
            } else {
                g[1] + " " + Phonetic.spellToken(ident, icao)
            }
        }

        // A taxiway / gate ident written flush against its number with no keyword in front
        // of it ("B1 CLSD BTWN B AND B2") — many fields publish the closure list bare, so
        // the TWY pass above never sees the ident. There is no word boundary inside the
        // token, so the digit rule at the end of the pipeline would voice the number glued
        // to the letter ("B1" -> "Bone", "B2" -> "Btwo"). Spell it phonetically, exactly as
        // the keyworded form already reads. Bounded to one or two letters and one or two
        // digits, so a longer coded token that survives to here is left alone.
        s = FLUSH_IDENT.replaceEach(s) { g -> Phonetic.spellToken(g[1] + g[2], icao) }

        // Bare taxiway idents in a closure NOTAM ("C B CLSD BTWN, B1 AND B2"). Without the
        // TWY keyword the letters survive to the synthesizer, which voices them as letter
        // names ("see", "bee") rather than the ident a controller says. Scoped to the
        // closure grammar so no stray capital is caught: an ident is a one/two-letter token
        // sitting in the short run immediately before CLSD/CLOSED, or between BTWN and AND.
        // Tokens that are common words or carry their own expansion (`nonTaxiwayTokens`)
        // are left for the passes below.
        s = CLOSURE_IDENT.replaceEach(s) { g -> taxiwayIdent(g[1], icao) }
        s = BETWEEN_IDENTS.replaceEach(s) { g ->
            g[1] + " " + taxiwayIdent(g[2], icao) + " AND " + taxiwayIdent(g[3], icao)
        }

        // "HAZD WX" / "HAZS WX" is the flight-service hazardous-weather advisory — the
        // adjective, not the noun a bare HAZD expands to in a NOTAM ("BIRD HAZD INVOF
        // ARPT"). Resolve it from the following WX before the abbreviation pass reaches
        // either token.
        s = HAZARDOUS_WX.replaceEach(s) { "hazardous weather" }

        // Units written flush against their number ("CRANE 155FT AGL", "GUSTS TO 30KT",
        // "WITHIN 5NM"). The abbreviation pass below is word-boundary anchored, so "155FT"
        // can never match "\bFT\b" — the unit would survive to be voiced as "F T". Split the
        // unit off its digits here (after the coded groups, which carry their own units,
        // have all been decoded) so both the number and the unit read correctly.
        s = FLUSH_UNIT.replaceEach(s) { g -> g[1] + " " + g[2] }

        // Standalone "VC" is the plain-language vicinity qualifier of an advisory ("BIRD
        // ACTIVITY VC OF ARPT", "BIRD ACTIVITY VC ARPT"). The weather pass above only
        // consumes a VC that prefixes a real weather code (VCSH, VCTS), so a lone token
        // survives to here and would otherwise be voiced letter by letter ("V C"). The
        // optional following "OF" is folded in so both published forms read the same
        // ("…vicinity of airport"); "\b" keeps VCTR/VCTRS/VCNTY out of the match.
        s = VICINITY.replaceEach(s) { "vicinity of" }

        // Expand the common ATIS abbreviations (word-boundary, so "APPROACHES" and
        // "DEPARTURE" are never clipped by the shorter "APCH"/"DEP" entries).
        for ((regex, expansion) in abbreviationPatterns) {
            s = regex.replace(s) { expansion }
        }

        // Any remaining digit run -> individual spoken digits (authentic ATIS style).
        s = DIGIT_RUN.replaceEach(s) { g -> Phonetic.spellDigits(g[0], icao) }

        // Drop any stray parentheses so TTS doesn't stumble over them.
        s = s.replace("(", " ").replace(")", " ")
        return collapseWhitespace(s).trim()
    }

    // region Coded-group renderers

    /**
     * Speak a coded wind group body ("00000", "VRB05", "25012", "090103") with an
     * optional gust ("G30") and unit ("KT"/"MPS").
     */
    fun spokenWind(body: String, gust: String, unit: String, icao: Boolean): String {
        val unitSuffix = if (unit == "MPS") " meters per second" else ""
        val gustPhrase =
            if (gust.startsWith("G")) " gusts " + spellCount(gust.drop(1), icao) else ""
        if (body == "00000") return "wind calm"
        if (body.startsWith("VRB")) {
            return "wind variable at " + spellCount(body.drop(3), icao) + gustPhrase + unitSuffix
        }
        val direction = body.take(3)
        val speed = body.drop(3)
        return "wind " + Phonetic.spellDigits(direction, icao) + " at " +
            spellCount(speed, icao) + gustPhrase + unitSuffix
    }

    /**
     * Speak a temperature/dewpoint field ("07" -> "seven", "M02" -> "minus two",
     * "19" -> "one niner"). Leading zeros are dropped, then digits are spoken
     * individually as on the air.
     */
    fun spokenTemp(s: String, icao: Boolean): String {
        var d = s
        var negative = false
        if (d.startsWith("M") || d.startsWith("-")) {
            negative = true
            d = d.drop(1)
        }
        val magnitude = spellCount(d, icao)
        return if (negative) "minus $magnitude" else magnitude
    }

    /**
     * Speak a sky-cover group the way an ATIS voice reads it (per the vATIS templates):
     * "few clouds at {h}", "{h} scattered", "{h} broken", "{h} overcast", "indefinite
     * ceiling {h}", with an optional cumulonimbus / towering-cumulus type appended.
     */
    fun spokenCloud(cover: String, hundreds: String, type: String, icao: Boolean): String {
        val feet = (hundreds.toIntOrNull() ?: 0) * 100
        val height = spokenHeight(feet, icao)
        val typeWord = when (type) {
            "CB" -> " cumulonimbus"
            "TCU" -> " towering cumulus"
            else -> ""
        }
        return when (cover) {
            "FEW" -> "few clouds at $height$typeWord"
            "SCT" -> "$height scattered$typeWord"
            "BKN" -> "$height broken$typeWord"
            "OVC" -> "$height overcast$typeWord"
            "VV" -> "indefinite ceiling $height"
            else -> "$cover $height"
        }
    }

    /**
     * Render a height in feet the way ATC speaks cloud bases / vertical visibility:
     * 800 -> "eight hundred", 1500 -> "one thousand five hundred", 25000 -> "two five
     * thousand". (Unlike `Phonetic.altitude`, never a flight level — cloud bases are
     * always read in plain feet.)
     */
    fun spokenHeight(feet: Int, icao: Boolean): String {
        val thousands = feet / 1000
        val hundreds = (feet % 1000) / 100
        val parts = mutableListOf<String>()
        if (thousands > 0) parts.add(Phonetic.spellDigits(thousands.toString(), icao) + " thousand")
        if (hundreds > 0) parts.add(Phonetic.spellDigits(hundreds.toString(), icao) + " hundred")
        return if (parts.isEmpty()) Phonetic.spellDigits("0", icao) else parts.joinToString(" ")
    }

    /**
     * Render a visibility fraction like "1/2" -> "one half", "3/4" -> "three quarters",
     * "5/8" -> "five eighths". Unknown denominators fall back to "<n> over <d>".
     */
    fun spokenFraction(numStr: String, denStr: String): String {
        val num = numStr.toIntOrNull()
        val den = denStr.toIntOrNull()
        if (num == null || den == null) return "$numStr over $denStr"
        val unit = when (den) {
            2 -> "half"
            4 -> "quarter"
            8 -> "eighth"
            16 -> "sixteenth"
            else -> return Phonetic.twoDigitGroup(num) + " over " + Phonetic.twoDigitGroup(den)
        }
        val word = Phonetic.twoDigitGroup(num)
        return if (num == 1) "$word $unit" else "$word ${unit}s"
    }

    /**
     * Speak an RVR group: "R28L/2400FT" -> "runway two eight left R V R two thousand
     * four hundred", with M/P (less/more than) and V (variable range) handled.
     */
    fun spokenRVR(runway: String, p1: String, v1: String, p2: String, v2: String, icao: Boolean): String {
        fun prefixWord(p: String): String = when (p) {
            "M" -> "less than "
            "P" -> "more than "
            else -> ""
        }
        val base = "runway " + Phonetic.runway(runway, icao) + " R V R "
        if (v2.isNotEmpty()) {
            // Variable range: "RVR variable {low} to {high}".
            return base + "variable " + prefixWord(p1) + spokenHeight(v1.toIntOrNull() ?: 0, icao) +
                " to " + prefixWord(p2) + spokenHeight(v2.toIntOrNull() ?: 0, icao)
        }
        return base + prefixWord(p1) + spokenHeight(v1.toIntOrNull() ?: 0, icao)
    }

    // endregion

    // region Weather phenomena

    /**
     * Descriptor codes (spoken before the phenomenon). `TS` reads standalone
     * ("thunderstorm"); the rest only qualify a following phenomenon.
     */
    private val descriptorWords = mapOf(
        "TS" to "thunderstorm", "FZ" to "freezing", "MI" to "shallow", "PR" to "partial",
        "BC" to "patches of", "DR" to "low drifting", "BL" to "blowing",
    )

    /** Precipitation / obscuration / other phenomena codes. */
    private val phenomenaWords = mapOf(
        "DZ" to "drizzle", "RA" to "rain", "SN" to "snow", "SG" to "snow grains",
        "IC" to "ice crystals", "PL" to "ice pellets", "GR" to "hail", "GS" to "small hail",
        "BR" to "mist", "FG" to "fog", "FU" to "smoke", "VA" to "volcanic ash",
        "DU" to "dust", "SA" to "sand", "HZ" to "haze", "PY" to "spray",
        "PO" to "dust whirls", "SQ" to "squalls", "FC" to "funnel cloud",
        "SS" to "sandstorm", "DS" to "duststorm",
    )

    /**
     * Alternation of every recognised two-letter weather code. Longest-match ordering is
     * irrelevant since all are two characters. Excludes `UP` (unknown precipitation) so
     * the English word "UP" in NOTAM text is never mistaken for weather.
     */
    private val weatherCodePattern: String =
        (descriptorWords.keys + phenomenaWords.keys + "SH").joinToString("|")

    /**
     * Decode a full weather group (already stripped of surrounding delimiters) such as
     * "+TSRA", "VCSH", "-SHRA", "FZFG", "BR". Returns null when the token isn't a valid
     * weather group (e.g. a lone descriptor like "BC"), so the caller leaves it intact.
     */
    fun decodeWeather(raw: String): String? {
        var body = raw
        var intensity: String? = null
        when {
            body.startsWith("+") -> { intensity = "heavy"; body = body.drop(1) }
            body.startsWith("-") -> { intensity = "light"; body = body.drop(1) }
        }
        var vicinity = false
        if (body.startsWith("VC")) { vicinity = true; body = body.drop(2) }
        if (body.isEmpty() || body.length % 2 != 0) return null

        val codes = body.chunked(2)
        if (!codes.all { it == "SH" || descriptorWords.containsKey(it) || phenomenaWords.containsKey(it) }) {
            return null
        }
        // A bare "GS" is far more often "glideslope" (e.g. "GS OTS") than small hail, which
        // in practice always carries intensity or another code ("-GS", "SHGS"). Leave a
        // lone, unqualified GS for the abbreviation pass.
        if (codes == listOf("GS") && intensity == null && !vicinity) return null
        // Likewise a bare "VA" in an ATIS body is nearly always the "visual approach" in the
        // approach list ("ILS 4R, VA 4L") rather than volcanic ash, which in practice carries
        // vicinity or sits among other observation groups ("VCVA"). Leave a lone, unqualified
        // VA for the abbreviation pass to read as "visual approach".
        if (codes == listOf("VA") && intensity == null && !vicinity) return null

        // A thunderstorm carries its own name; any intensity belongs to the precipitation
        // that comes with it ("+TSRA" -> "thunderstorm with heavy rain"), so pull TS out and
        // build the precipitation phrase from the remaining codes.
        val hasThunderstorm = codes.contains("TS")
        val words = mutableListOf<String>()
        var showers = false
        var hasPhenomenon = false
        for (code in codes) {
            if (code == "TS") continue
            when {
                code == "SH" -> showers = true
                descriptorWords.containsKey(code) -> words.add(descriptorWords.getValue(code))
                phenomenaWords.containsKey(code) -> {
                    words.add(phenomenaWords.getValue(code))
                    hasPhenomenon = true
                }
            }
        }
        // A lone qualifying descriptor ("BC", "FZ", "BL"…) with nothing to qualify isn't a
        // weather report here — leave it for the abbreviation pass.
        if (!(hasThunderstorm || hasPhenomenon || showers || intensity != null || vicinity)) {
            return null
        }

        if (showers) words.add("showers")
        var precip = words.joinToString(" ")
        if (intensity != null && precip.isNotEmpty()) precip = "$intensity $precip"

        var phrase = if (hasThunderstorm) {
            if (precip.isEmpty()) "thunderstorm" else "thunderstorm with $precip"
        } else {
            precip
        }
        if (vicinity) phrase += " in the vicinity"
        return phrase.trim()
    }

    // endregion

    /**
     * Interpret a 6-digit day-stamped observation stamp ("042252" = day 04, 2252Z),
     * returning the "HHMM" time portion, or null when the digits aren't a valid stamp
     * (so an unrelated 6-digit run is left untouched).
     */
    private fun dayStampedTime(s: String): String? {
        if (s.length != 6 || !s.all { it.isDigit() }) return null
        val day = s.substring(0, 2).toIntOrNull() ?: return null
        val hour = s.substring(2, 4).toIntOrNull() ?: return null
        val minute = s.substring(4, 6).toIntOrNull() ?: return null
        if (day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        return s.takeLast(4)
    }

    /**
     * Two-letter uppercase tokens that can immediately follow "TWY"/"TWYS" in coded ATIS
     * text but are **not** taxiway identifiers — common English words ("IN USE", "TO",
     * "AT") and abbreviations/compass points that have their own expansion ("SW", "HS",
     * "WS"). These are left for the word/abbreviation passes instead of being spelled
     * phonetically.
     */
    private val nonTaxiwayTokens = setOf(
        "IN", "TO", "AT", "IS", "OR", "ON", "OF", "BY", "UP", "NO", "AS", "IT", "AN", "BE",
        "NE", "NW", "SE", "SW", "HS", "WS", "MU", "GS", "BA", "FT", "WX", "OM", "MM", "IM",
    )

    /**
     * Spell a bare closure-NOTAM taxiway ident phonetically ("B" -> "Bravo"), leaving a
     * token that is a common word or carries its own expansion (`nonTaxiwayTokens`)
     * untouched.
     */
    private fun taxiwayIdent(token: String, icao: Boolean): String =
        if (token in nonTaxiwayTokens) token else Phonetic.spellToken(token, icao)

    private val RUNWAY_SIDES = mapOf("L" to "left", "R" to "right", "C" to "center")

    // region Abbreviation table

    /**
     * Common D-ATIS abbreviations → spoken words. Multi-letter identifiers that should be
     * spelled on the air (ILS, RNAV, GPS…) expand to space-separated letters so the
     * synthesizer says "I L S" rather than "ils". `\b` boundaries keep a short entry from
     * clipping a longer word.
     *
     * Order is significant: the list is applied top to bottom, so a longer entry must
     * precede any shorter one it contains.
     */
    private val abbreviations: List<Pair<String, String>> = listOf(
        "RWYS" to "runways", "RWY" to "runway", "RY" to "runway", "RWYCC" to "runway condition code",
        "TWYS" to "taxiways", "TWY" to "taxiway", "TY" to "taxiway",
        "APCHS" to "approaches", "APCH" to "approach", "APPCH" to "approach",
        "APPR" to "approach", "APPS" to "approaches", "APP" to "approach", "APPCHS" to "approaches",
        "DEPS" to "departures", "DEPG" to "departing", "DEPTG" to "departing", "DPTG" to "departing",
        "DEPTURE" to "departure", "DEP" to "departure",
        "ARRS" to "arrivals", "ARR" to "arrival",
        "LDG" to "landing", "LNDG" to "landing", "TKOF" to "takeoff", "TKOFF" to "takeoff",
        "ILS" to "I L S", "LOC" to "localizer", "RNAV" to "R NAV", "RNP" to "R N P", "GPS" to "G P S",
        "VOR" to "V O R", "DME" to "D M E", "NDB" to "N D B", "PRM" to "P R M",
        "LDA" to "L D A", "SDF" to "S D F", "BC" to "back course",
        // In the spoken D-ATIS body the observed visibility is always the coded group
        // (e.g. "10SM"), so a bare "VIS" is the approach kind — "VIS APP" = visual approach.
        // "VA" is the compact approach-list form of visual approach ("ILS 4R, VA 4L"); the
        // weather pass leaves a lone VA alone (see decodeWeather) so it reads here.
        "VIS" to "visual", "VA" to "visual approach",
        "VCTR" to "vector", "VCTRS" to "vectors", "PROG" to "progress",
        // Approach-intercept wording: "EXP 2 INTCP THE ILS Y RY 10R FNA CRS" reads
        // "…intercept the ILS Yankee runway one zero right final course".
        "INTCP" to "intercept", "FNA" to "final", "CRS" to "course",
        "INTL" to "international", "INTXN" to "intersection", "INTX" to "intersection", "APRN" to "apron",
        "CLSD" to "closed", "CTC" to "contact", "FREQ" to "frequency", "FREQS" to "frequencies",
        "INFO" to "information", "ADVS" to "advise", "ADVZ" to "advise", "ADZ" to "advise",
        "ADVSD" to "advised", "ADZYS" to "advisories", "ADVZY" to "advisory",
        "TEMP" to "temperature", "DWPT" to "dewpoint", "DEWPT" to "dewpoint",
        "WX" to "weather", "TFC" to "traffic", "CIG" to "ceiling",
        // The observed altimeter is always coded (A####), so a bare "ALT" in the body is an
        // assigned altitude ("read back HS and ALT"); "ALSTG" is the altimeter setting.
        "ALSTG" to "altimeter", "ALT" to "altitude",
        "MAINT" to "maintenance", "HDG" to "heading", "HDGS" to "headings",
        "DRCTN" to "direction", "ATTN" to "attention",
        "BRKG" to "braking", "BA" to "braking action", "SFC" to "surface",
        // Runway condition-code reports read "RWY 22L, COND CODE, 5 5 5 AT 1630Z". The
        // observed altimeter/visibility are coded groups, so a bare "COND" in the body is
        // always the surface/field condition ("condition code", "field condition").
        "COND" to "condition",
        "OTS" to "out of service", "UNAVBL" to "unavailable", "UNAVAIL" to "unavailable",
        "AVBL" to "available", "AVL" to "available", "AVLB" to "available", "AVAIL" to "available",
        "SIMUL" to "simultaneous", "SIMULT" to "simultaneous", "SIMO" to "simultaneous",
        "CONV" to "converging", "PARL" to "parallel", "DPNDNT" to "dependent", "DPENDT" to "dependent",
        "TWR" to "tower", "GND" to "ground", "FSS" to "flight service station",
        // "GC" is ground control and "A/S" the terminal airside, both used in the
        // "…CTC GC 121.8" ramp-handoff lines. "FLT SVC FREQ" is the flight-service frequency
        // the hazardous-weather advisory points at.
        "GC" to "ground control", "GA" to "general aviation", "A/S" to "airside",
        "FLT" to "flight", "SVCS" to "services", "SVC" to "service",
        "CLNC" to "clearance", "CLRNC" to "clearance",
        "DEL" to "delivery", "CTL" to "control", "CTLR" to "controller", "CTRL" to "control",
        "ATC" to "A T C",
        "ACFT" to "aircraft", "EQUIP" to "equipment", "EQPT" to "equipment",
        "PERS" to "personnel", "PERSONNEL" to "personnel", "VEH" to "vehicles",
        "CONST" to "construction", "CONSTR" to "construction", "OPS" to "operations",
        "OPER" to "operate", "OPR" to "operate",
        "EXP" to "expect", "EXPC" to "expect", "EXPCT" to "expect",
        "XPECT" to "expect", "XPCT" to "expect",
        "XPDR" to "transponder", "XPNDR" to "transponder", "TRNSPNDR" to "transponder",
        "MODEC" to "mode charlie",
        // Surface-surveillance / equipage acronyms read on the air as spelled letters. Both
        // the hyphenated and unhyphenated feed spellings expand ("ADS-B"/"ADSB",
        // "ASDE-X"/"ASDEX").
        "ADS-B" to "A D S B", "ADSB" to "A D S B", "ASDE-X" to "A S D E X", "ASDEX" to "A S D E X",
        "BTN" to "between", "BTWN" to "between", "FT" to "feet", "KTS" to "knots", "KT" to "knots",
        "NM" to "nautical miles", "AGL" to "A G L", "MSL" to "M S L",
        "HLDG" to "holding", "DLA" to "delay", "DLY" to "delay", "DLAY" to "delay",
        "NE" to "northeast", "NW" to "northwest", "SE" to "southeast", "SW" to "southwest",
        "CB" to "cumulonimbus", "TCU" to "towering cumulus",
        "WS" to "wind shear", "LLWS" to "low level wind shear", "WSHFT" to "wind shift",
        "MU" to "M U", "RCC" to "runway condition code", "RVR" to "R V R",
        "SKC" to "sky clear", "CLR" to "clear below one two thousand", "NSC" to "no significant clouds",
        "NCD" to "no clouds detected",
        "PIREP" to "pilot report", "PIREPS" to "pilot reports",
        "ARPT" to "airport", "ARPTS" to "airports", "INVOF" to "in vicinity of",
        "VCNTY" to "vicinity", "VCY" to "vicinity", "CTN" to "caution", "CAUT" to "caution",
        "NUM" to "numerous", "THSD" to "thousand", "THND" to "thousand", "HND" to "hundred",
        "CONT" to "continuous", "CONTINUOS" to "continuous",
        "LAHSO" to "land and hold short operations", "EFCT" to "effect",
        "IM" to "inner marker", "MM" to "middle marker", "OM" to "outer marker", "GS" to "glideslope",
        "NOTAMS" to "notams", "NOTAM" to "notam", "RDBK" to "read back", "READBACK" to "read back",
        "INSTRCNS" to "instructions", "INSTRCN" to "instruction",
        "INSTRS" to "instructions", "INSTR" to "instruction",
        "OTHRWSE" to "otherwise", "OTHW" to "otherwise",
        // Hold short appears as both "HS" and the slashed "H/S"; the slash is a literal in
        // the escaped pattern, so "H/S" needs its own entry ("\bHS\b" can't reach across it).
        "HS" to "hold short", "H/S" to "hold short",
        "HAZDS" to "hazards", "HAZD" to "hazard", "HAZS" to "hazardous",
    )

    /** The abbreviation table compiled once — ~180 patterns, applied to every broadcast. */
    private val abbreviationPatterns: List<Pair<Regex, String>> =
        abbreviations.map { (abbreviation, expansion) ->
            Regex("\\b" + Regex.escape(abbreviation) + "\\b") to expansion
        }

    // endregion

    // region Regex helpers

    private val PARENTHETICAL = Regex("\\(([A-Z0-9 ]*)\\)")
    private val REMARKS = Regex("\\bRMK\\b[^.]*")
    private val INFO_LETTER = Regex("\\b(?:INFORMATION|INFO)\\s+([A-Z])\\b")
    private val ZULU = Regex("\\b(\\d{3,4})Z\\b")
    private val DAY_STAMP = Regex("\\b(\\d{6})\\b")
    private val ALTIMETER = Regex("\\bA(\\d{4})\\b")
    private val QNH = Regex("\\bQ(\\d{4})\\b")
    private val WIND = Regex("\\b(00000|VRB\\d{2,3}|\\d{5,6})(G\\d{2,3})?(KT|MPS)\\b")
    private val WIND_VARIABLE = Regex("\\b(\\d{3})V(\\d{3})\\b")
    private val VIS_MORE = Regex("\\bP(\\d{1,2})SM\\b")
    private val VIS_LESS_FRACTION = Regex("\\bM(\\d{1,2})/(\\d{1,2})SM\\b")
    private val VIS_MIXED_FRACTION = Regex("\\b(\\d{1,2}) (\\d{1,2})/(\\d{1,2})SM\\b")
    private val VIS_FRACTION = Regex("\\b(\\d{1,2})/(\\d{1,2})SM\\b")
    private val VIS_WHOLE = Regex("\\b(\\d{1,3})SM\\b")
    private val RVR = Regex("\\bR(\\d{2}[LRC]?)/([MP]?)(\\d{3,4})(?:V([MP]?)(\\d{3,4}))?FT\\b")
    private val TEMP_DEWPOINT = Regex("\\b([M-]?\\d{2})/([M-]?\\d{2})\\b")
    private val CLOUD = Regex("\\b(FEW|SCT|BKN|OVC|VV)(\\d{3})(CB|TCU)?\\b")
    private val WEATHER_GROUP =
        Regex("(?<=[\\s,(/])([+\\-]?(?:VC)?(?:$weatherCodePattern)+)(?=[\\s,.)/])")
    private val FREQUENCY = Regex("\\b(\\d{2,3})\\.(\\d{1,3})\\b")
    private val FLUSH_RUNWAY_KEYWORD = Regex("\\b(RWYS|RWY|RYS|RY)(\\d{1,2}[LRC]?)\\b")
    private val RUNWAY_DESIGNATOR = Regex("\\b(\\d{1,2})([LRC])\\b")
    private val APPROACH_VARIANT = Regex("\\b(ILS|RNAV|RNP|LOC|VOR|LDA|SDF)\\s+([A-Z])\\b")
    private val TAXIWAY_KEYWORD =
        Regex("\\b(TWYS|TWY|TAXIWAY|TAXIWAYS|TY)[,\\s]+([A-Z]{1,2}\\d{0,2})\\b")
    private val FLUSH_IDENT = Regex("\\b([A-Z]{1,2})(\\d{1,2})\\b")
    private val CLOSURE_IDENT =
        Regex("\\b([A-Z]{1,2})\\b(?=(?:[,\\s]+[A-Z]{1,2}\\b){0,2}[,\\s]+(?:CLSD|CLOSED)\\b)")
    private val BETWEEN_IDENTS =
        Regex("\\b(BTWN|BTN)[,\\s]+([A-Z]{1,2})\\b[,\\s]+AND[,\\s]+([A-Z]{1,2})\\b")
    private val HAZARDOUS_WX = Regex("\\b(?:HAZDS?|HAZS)\\s+WX\\b")
    private val FLUSH_UNIT = Regex("\\b(\\d+)(FT|KTS|KT|NM)\\b")
    private val VICINITY = Regex("\\bVC\\b(?:\\s+OF\\b)?")
    private val DIGIT_RUN = Regex("\\d+")
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Whether a parenthetical's contents are entirely spelled-out number words (an
     * altimeter/pressure readback), e.g. "TWO NINER NINER TWO".
     */
    private fun isNumberReadback(content: String): Boolean {
        val words = content.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { it in numberWords || it.all(Char::isDigit) }
    }

    /**
     * Uppercase set of every spoken number word across both phraseology packs, plus the
     * magnitude words used in readbacks.
     */
    private val numberWords: Set<String> = buildSet {
        Phonetic.digitWords.values.forEach { add(it.uppercase()) }
        Phonetic.icaoDigitWords.values.forEach { add(it.uppercase()) }
        addAll(listOf("NINE", "HUNDRED", "THOUSAND", "POINT", "DECIMAL"))
    }

    /**
     * Spell a numeric string one digit at a time after dropping leading zeros, so a coded
     * "08" reads "eight" and "12" reads "one two" (but "00" still reads "zero").
     */
    private fun spellCount(s: CharSequence, icao: Boolean): String {
        val stripped = s.dropWhile { it == '0' }.toString()
        return Phonetic.spellDigits(stripped.ifEmpty { "0" }, icao)
    }

    private fun collapseWhitespace(s: String): String = WHITESPACE_RUN.replace(s, " ")

    /**
     * Replace every match with the result of [transform], which receives the match's
     * capture groups (index 0 is the whole match; a group that didn't participate reads
     * as an empty string, matching the Swift helper's NSNotFound handling).
     *
     * The replacement is inserted literally — `Regex.replace` with a lambda does no `$`
     * group expansion, which matters because expansions like "R V R" and decoded weather
     * text can contain any character.
     */
    private fun Regex.replaceEach(input: String, transform: (List<String>) -> String): String =
        replace(input) { transform(it.groupValues) }

    // endregion
}
