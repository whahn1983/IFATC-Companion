import Foundation
import CoreLocation

/// Parses PIREPs/AIREPs from the Aviation Weather Center JSON API.
enum PIREPParser {

    static func parseJSON(_ data: Data) -> [PIREP] {
        JSONLenient.array(data).compactMap { obj in
            var p = PIREP(raw: JSONLenient.string(obj["rawOb"]) ?? JSONLenient.string(obj["raw_text"]) ?? "")
            if let lat = JSONLenient.double(obj["lat"]), let lon = JSONLenient.double(obj["lon"]) {
                p.coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
            }
            // Altitude may be in hundreds of feet or full feet depending on field.
            if let fl = JSONLenient.int(obj["fltlvl"]) ?? JSONLenient.int(obj["altitude"]) {
                p.altitudeFt = fl < 1000 ? fl * 100 : fl
            }
            p.time = JSONLenient.date(obj["obsTime"]) ?? JSONLenient.date(obj["receiptTime"])
            p.aircraftType = JSONLenient.string(obj["acType"]) ?? JSONLenient.string(obj["actype"])
            // Turbulence: explicit field, then turbulence object, then raw text.
            if let tbInt = JSONLenient.int(obj["tbInt1"]) {
                p.turbulence = TurbulenceSeverity(rawValue: min(4, max(0, tbInt)))
            } else if let tb = JSONLenient.string(obj["turbulence"]) {
                p.turbulence = TurbulenceSeverity.parse(tb)
            } else {
                p.turbulence = TurbulenceSeverity.parse(p.raw)
            }
            p.icing = JSONLenient.string(obj["icing"]) ?? JSONLenient.string(obj["icgInt1"])
            return p
        }
    }
}
