package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate
import kotlinx.serialization.json.JsonElement

/**
 * Parses PIREPs/AIREPs from the Aviation Weather Center JSON API.
 *
 * Ported from `IFATCCompanion/Weather/PIREPParser.swift`.
 */
object PirepParser {

    fun parseJson(data: ByteArray): List<PIREP> = JsonLenient.array(data).map { obj ->
        val p = PIREP(
            raw = JsonLenient.string(obj["rawOb"]) ?: JsonLenient.string(obj["raw_text"]) ?: "",
        )
        val lat = JsonLenient.double(obj["lat"])
        val lon = JsonLenient.double(obj["lon"])
        if (lat != null && lon != null) {
            p.coordinate = Coordinate(latitude = lat, longitude = lon)
        }
        // Flight level in hundreds of feet (AWC `fltLvl`, e.g. 190 → 19000 ft). A
        // value of 0 goes with a during-climb/descent type (`fltLvlType` DURC/DURD)
        // and means "level unknown", so leave altitude null rather than clamping it to
        // sea level — otherwise the ±band relevance filter would wrongly reject it.
        // `fltlvl` / `altitude` are kept as tolerant fallbacks.
        val fl = JsonLenient.int(obj["fltLvl"]) ?: JsonLenient.int(obj["fltlvl"])
            ?: JsonLenient.int(obj["altitude"])
        if (fl != null && fl > 0) {
            p.altitudeFt = if (fl < 1000) fl * 100 else fl
        }
        p.timeMillis = JsonLenient.date(obj["obsTime"]) ?: JsonLenient.date(obj["receiptTime"])
        p.aircraftType = JsonLenient.string(obj["acType"]) ?: JsonLenient.string(obj["actype"])
        // Turbulence intensity is a *code string* ("LGT" / "MOD" / "SEV" / "NEG" / ""),
        // not a number, and up to two layers may be reported — take the worse. Fall
        // back to a generic field, then to scraping the raw text.
        val tb1 = turbulence(obj["tbInt1"])
        val tb2 = turbulence(obj["tbInt2"])
        val worst = listOfNotNull(tb1, tb2).maxOrNull()
        if (worst != null) {
            p.turbulence = worst
        } else {
            val tb = turbulence(obj["turbulence"])
            p.turbulence = tb ?: TurbulenceSeverity.parse(p.raw)
        }
        p.icing = nonEmpty(obj["icgInt1"]) ?: nonEmpty(obj["icgInt2"]) ?: nonEmpty(obj["icing"])
        p
    }

    /** Parse a turbulence intensity code, treating empty/whitespace as "not reported". */
    private fun turbulence(value: JsonElement?): TurbulenceSeverity? {
        val code = nonEmpty(value) ?: return null
        return TurbulenceSeverity.parse(code)
    }

    /**
     * The trimmed string value when it is non-empty, else null (AWC returns `""` for
     * absent coded fields, which must not be treated as a real value).
     */
    private fun nonEmpty(value: JsonElement?): String? =
        JsonLenient.string(value)?.trim { it == ' ' || it == '\t' }?.takeIf { it.isNotEmpty() }
}
