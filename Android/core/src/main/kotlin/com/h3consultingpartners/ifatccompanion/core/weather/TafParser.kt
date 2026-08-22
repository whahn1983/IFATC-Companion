package com.h3consultingpartners.ifatccompanion.core.weather

/**
 * Parses TAFs from the Aviation Weather Center JSON API (best-effort).
 *
 * Ported from `IFATCCompanion/Weather/TAFParser.swift`.
 */
object TafParser {

    fun parseJson(data: ByteArray): List<TAF> = JsonLenient.array(data).mapNotNull { obj ->
        val icao = JsonLenient.string(obj["icaoId"]) ?: JsonLenient.string(obj["station_id"])
            ?: return@mapNotNull null
        val taf = TAF(
            icao = icao,
            raw = JsonLenient.string(obj["rawTAF"]) ?: JsonLenient.string(obj["raw_text"]) ?: "",
        )
        taf.issueTimeMillis =
            JsonLenient.date(obj["issueTime"]) ?: JsonLenient.date(obj["bulletinTime"])
        JsonLenient.objectArray(obj["fcsts"])?.let { fcsts ->
            taf.periods = fcsts.map { f ->
                TAFForecastPeriod(
                    raw = JsonLenient.string(f["rawTAF"]) ?: "",
                    windDirection = JsonLenient.int(f["wdir"]),
                    windSpeed = JsonLenient.int(f["wspd"]),
                    visibilitySM = JsonLenient.double(f["visib"]),
                    changeIndicator = JsonLenient.string(f["fcstChange"]),
                )
            }
        }
        taf
    }
}
