package com.h3consultingpartners.ifatccompanion.core.airports

import com.h3consultingpartners.ifatccompanion.core.geo.Coordinate

/**
 * Tiny built-in airport coordinate lookup. Not exhaustive — covers the mock
 * routes and common US hubs so route/weather math works offline. Manual ICAO
 * entry still works for airports not listed (distance math simply skips them).
 *
 * Ported from `IFATCCompanion/Models/AirportDatabase.swift`. The Swift type is a
 * struct with a `shared` singleton; Kotlin expresses the same thing as an `object`,
 * so `AirportDatabase.coordinate("KIAH")` replaces `AirportDatabase.shared.coordinate(for:)`.
 */
object AirportDatabase {

    data class Airport(
        val icao: String,
        val name: String,
        val coordinate: Coordinate,
    )

    /** ICAO (4-letter) -> airport record. */
    val airports: Map<String, Airport>

    init {
        val list: List<Airport> = listOf(
            Airport("KIAH", "Houston Intercontinental", Coordinate(29.9844, -95.3414)),
            Airport("KMSP", "Minneapolis–St. Paul", Coordinate(44.8848, -93.2223)),
            Airport("KDEN", "Denver International", Coordinate(39.8561, -104.6737)),
            Airport("KORD", "Chicago O'Hare", Coordinate(41.9742, -87.9073)),
            Airport("KATL", "Atlanta Hartsfield", Coordinate(33.6407, -84.4277)),
            Airport("KLAX", "Los Angeles", Coordinate(33.9416, -118.4085)),
            Airport("KJFK", "New York JFK", Coordinate(40.6413, -73.7781)),
            Airport("KEWR", "Newark Liberty", Coordinate(40.6925, -74.1687)),
            Airport("KLGA", "New York LaGuardia", Coordinate(40.7769, -73.8740)),
            Airport("KSFO", "San Francisco", Coordinate(37.6213, -122.3790)),
            Airport("KSEA", "Seattle-Tacoma", Coordinate(47.4502, -122.3088)),
            Airport("KDFW", "Dallas–Fort Worth", Coordinate(32.8998, -97.0403)),
            Airport("KBOS", "Boston Logan", Coordinate(42.3656, -71.0096)),
            Airport("KMIA", "Miami International", Coordinate(25.7959, -80.2870)),
            Airport("KLAS", "Las Vegas Harry Reid", Coordinate(36.0840, -115.1537)),
            Airport("KPHX", "Phoenix Sky Harbor", Coordinate(33.4342, -112.0116)),
            Airport("KDCA", "Washington Reagan", Coordinate(38.8512, -77.0402)),
            Airport("KMCI", "Kansas City", Coordinate(39.2976, -94.7139)),
            Airport("KSTL", "St. Louis Lambert", Coordinate(38.7487, -90.3700)),
            Airport("KOMA", "Omaha Eppley", Coordinate(41.3032, -95.8941)),
            Airport("KDSM", "Des Moines", Coordinate(41.5340, -93.6631)),
        )
        airports = list.associateBy { it.icao }
    }

    fun coordinate(icao: String): Coordinate? = airports[icao.uppercase()]?.coordinate

    fun name(icao: String): String? = airports[icao.uppercase()]?.name
}
