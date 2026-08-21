package com.h3consultingpartners.ifatccompanion.core.weather

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate

/**
 * Parses AIRMET/SIGMET/G-AIRMET hazard products from the Aviation Weather Center
 * JSON API (best-effort; coverage and schema vary by product).
 *
 * Ported from `IFATCCompanion/Weather/SIGMETParser.swift`.
 */
object SigmetParser {

    fun parseJson(data: ByteArray): List<SIGMET> = JsonLenient.array(data).mapNotNull { obj ->
        val s = SIGMET(
            raw = JsonLenient.string(obj["rawSigmet"])
                ?: JsonLenient.string(obj["rawAirSigmet"])
                ?: JsonLenient.string(obj["raw_text"])
                ?: "",
        )
        s.hazard = JsonLenient.string(obj["hazard"]) ?: JsonLenient.string(obj["hazardType"])
        s.severity = JsonLenient.string(obj["severity"])
        JsonLenient.objectArray(obj["coords"])?.let { coords ->
            s.area = coords.mapNotNull { c ->
                val lat = JsonLenient.double(c["lat"]) ?: return@mapNotNull null
                val lon = JsonLenient.double(c["lon"]) ?: return@mapNotNull null
                Coordinate(latitude = lat, longitude = lon)
            }
        }
        if (s.raw.isEmpty() && s.hazard == null) return@mapNotNull null
        s
    }
}
