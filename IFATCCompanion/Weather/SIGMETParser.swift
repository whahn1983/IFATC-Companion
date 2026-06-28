import Foundation
import CoreLocation

/// Parses AIRMET/SIGMET/G-AIRMET hazard products from the Aviation Weather Center
/// JSON API (best-effort; coverage and schema vary by product).
enum SIGMETParser {

    static func parseJSON(_ data: Data) -> [SIGMET] {
        JSONLenient.array(data).compactMap { obj in
            var s = SIGMET(raw: JSONLenient.string(obj["rawSigmet"]) ?? JSONLenient.string(obj["rawAirSigmet"]) ?? JSONLenient.string(obj["raw_text"]) ?? "")
            s.hazard = JSONLenient.string(obj["hazard"]) ?? JSONLenient.string(obj["hazardType"])
            s.severity = JSONLenient.string(obj["severity"])
            if let coords = obj["coords"] as? [[String: Any]] {
                s.area = coords.compactMap { c in
                    guard let lat = JSONLenient.double(c["lat"]), let lon = JSONLenient.double(c["lon"]) else { return nil }
                    return CLLocationCoordinate2D(latitude: lat, longitude: lon)
                }
            }
            if s.raw.isEmpty && s.hazard == nil { return nil }
            return s
        }
    }
}
