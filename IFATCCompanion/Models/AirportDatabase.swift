import Foundation
import CoreLocation

/// Tiny built-in airport coordinate lookup. Not exhaustive — covers the mock
/// routes and common US hubs so route/weather math works offline. Manual ICAO
/// entry still works for airports not listed (distance math simply skips them).
struct AirportDatabase {

    struct Airport {
        let icao: String
        let name: String
        let coordinate: CLLocationCoordinate2D
    }

    static let shared = AirportDatabase()

    let airports: [String: Airport]

    init() {
        let list: [(String, String, Double, Double)] = [
            ("KIAH", "Houston Intercontinental", 29.9844, -95.3414),
            ("KMSP", "Minneapolis–St. Paul", 44.8848, -93.2223),
            ("KDEN", "Denver International", 39.8561, -104.6737),
            ("KORD", "Chicago O'Hare", 41.9742, -87.9073),
            ("KATL", "Atlanta Hartsfield", 33.6407, -84.4277),
            ("KLAX", "Los Angeles", 33.9416, -118.4085),
            ("KJFK", "New York JFK", 40.6413, -73.7781),
            ("KEWR", "Newark Liberty", 40.6925, -74.1687),
            ("KLGA", "New York LaGuardia", 40.7769, -73.8740),
            ("KSFO", "San Francisco", 37.6213, -122.3790),
            ("KSEA", "Seattle-Tacoma", 47.4502, -122.3088),
            ("KDFW", "Dallas–Fort Worth", 32.8998, -97.0403),
            ("KBOS", "Boston Logan", 42.3656, -71.0096),
            ("KMIA", "Miami International", 25.7959, -80.2870),
            ("KLAS", "Las Vegas Harry Reid", 36.0840, -115.1537),
            ("KPHX", "Phoenix Sky Harbor", 33.4342, -112.0116),
            ("KDCA", "Washington Reagan", 38.8512, -77.0402),
            ("KMCI", "Kansas City", 39.2976, -94.7139),
            ("KSTL", "St. Louis Lambert", 38.7487, -90.3700),
            ("KOMA", "Omaha Eppley", 41.3032, -95.8941),
            ("KDSM", "Des Moines", 41.5340, -93.6631)
        ]
        var dict: [String: Airport] = [:]
        for (icao, name, lat, lon) in list {
            dict[icao] = Airport(icao: icao, name: name,
                                 coordinate: CLLocationCoordinate2D(latitude: lat, longitude: lon))
        }
        airports = dict
    }

    func coordinate(for icao: String) -> CLLocationCoordinate2D? {
        airports[icao.uppercased()]?.coordinate
    }

    func name(for icao: String) -> String? {
        airports[icao.uppercased()]?.name
    }
}
