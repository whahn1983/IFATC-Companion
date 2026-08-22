package com.h3consultingpartners.ifatccompanion.core.weather

/**
 * Parses METARs from the Aviation Weather Center JSON API and from raw text.
 *
 * Ported from `IFATCCompanion/Weather/METARParser.swift`.
 */
object MetarParser {

    /** hPa → inHg. The exact factor the iOS parser uses; do not round it. */
    private const val HPA_TO_INHG = 0.0295299830714

    fun parseJson(data: ByteArray): List<METAR> = JsonLenient.array(data).mapNotNull { obj ->
        val icao = JsonLenient.string(obj["icaoId"]) ?: JsonLenient.string(obj["station_id"])
            ?: return@mapNotNull null
        val m = METAR(
            icao = icao,
            raw = JsonLenient.string(obj["rawOb"]) ?: JsonLenient.string(obj["raw_text"]) ?: "",
        )
        m.observationTimeMillis =
            JsonLenient.date(obj["reportTime"]) ?: JsonLenient.date(obj["obsTime"])
        m.windDirection = JsonLenient.int(obj["wdir"])
        m.windSpeed = JsonLenient.int(obj["wspd"])
        m.windGust = JsonLenient.int(obj["wgst"])
        m.visibilitySM = JsonLenient.double(obj["visib"])
        JsonLenient.double(obj["altim"])?.let { altimHpa ->
            // AWC reports altimeter in hPa; convert to inHg.
            m.altimeterInHg = if (altimHpa > 100) altimHpa * HPA_TO_INHG else altimHpa
        }
        m.temperatureC = JsonLenient.double(obj["temp"])
        m.dewpointC = JsonLenient.double(obj["dewp"])
        m.flightCategory = JsonLenient.string(obj["fltCat"])
        JsonLenient.objectArray(obj["clouds"])?.let { clouds ->
            m.clouds = clouds.mapNotNull { c ->
                val cover = JsonLenient.string(c["cover"]) ?: return@mapNotNull null
                CloudLayer(cover = cover, baseFt = JsonLenient.int(c["base"]))
            }.toMutableList()
        }
        if (m.raw.isEmpty() == false && m.windDirection == null) {
            // Backfill from raw if structured fields were missing. Note the wind speed
            // is overwritten with the backfill's value even when it was already known —
            // ported as-is, because that is what the shipping iOS parser does.
            val backfill = parseRaw(m.raw)
            m.windDirection = backfill?.windDirection
            m.windSpeed = backfill?.windSpeed
        }
        m
    }

    /** Deterministic raw METAR text parser (subset sufficient for ATC phraseology). */
    fun parseRaw(raw: String): METAR? {
        // Swift's `split(separator: " ")` omits empty subsequences.
        val tokens = raw.split(" ").filter { it.isNotEmpty() }
        if (tokens.size < 2) return null
        var idx = 0
        // Station id (skip optional "METAR"/"SPECI" prefix).
        if (tokens[0] == "METAR" || tokens[0] == "SPECI") idx = 1
        if (idx >= tokens.size) return null
        val icao = tokens[idx]
        val m = METAR(icao = icao, raw = raw)

        for (token in tokens) {
            // Wind: dddssKT or dddssGggKT or VRBssKT
            if (token.endsWith("KT") || token.endsWith("MPS")) {
                val body = token.replace("KT", "").replace("MPS", "")
                if (body.startsWith("VRB")) {
                    m.windDirection = 0
                    m.windSpeed = body.drop(3).take(2).toIntOrNull()
                } else if (body.length >= 5) {
                    m.windDirection = body.take(3).toIntOrNull()
                    val rest = body.drop(3)
                    val gIndex = rest.indexOf('G')
                    if (gIndex >= 0) {
                        m.windSpeed = rest.substring(0, gIndex).toIntOrNull()
                        m.windGust = rest.substring(gIndex + 1).toIntOrNull()
                    } else {
                        m.windSpeed = rest.toIntOrNull()
                    }
                }
            } else if (token.endsWith("SM")) {
                val body = token.replace("SM", "")
                m.visibilitySM = JsonLenient.double(body)
            } else if (token.startsWith("A") && token.length == 5 &&
                token.drop(1).toIntOrNull() != null
            ) {
                m.altimeterInHg = token.drop(1).toInt().toDouble() / 100.0
            } else if (token.startsWith("Q") && token.length == 5 &&
                token.drop(1).toIntOrNull() != null
            ) {
                m.altimeterInHg = token.drop(1).toInt().toDouble() * HPA_TO_INHG
            } else if (isCloudToken(token)) {
                val cover = token.take(3)
                val baseStr = token.drop(3).take(3)
                val base = baseStr.toIntOrNull()?.let { it * 100 }
                m.clouds.add(CloudLayer(cover = cover, baseFt = base))
            } else if (token.contains("/") &&
                (token.firstOrNull() == 'M' || token.firstOrNull()?.isDigit() == true) &&
                token.length <= 7
            ) {
                val parts = token.split("/")
                if (parts.size == 2) {
                    m.temperatureC = decodeTemp(parts[0])
                    m.dewpointC = decodeTemp(parts[1])
                }
            }
        }
        return m
    }

    private fun isCloudToken(token: String): Boolean =
        listOf("FEW", "SCT", "BKN", "OVC").any { token.startsWith(it) }

    private fun decodeTemp(s: String): Double? {
        if (s.startsWith("M")) return s.drop(1).toDoubleOrNull()?.let { -it }
        return s.toDoubleOrNull()
    }
}
