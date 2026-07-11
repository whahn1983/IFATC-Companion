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
            // Flight level in hundreds of feet (AWC `fltLvl`, e.g. 190 → 19000 ft). A
            // value of 0 goes with a during-climb/descent type (`fltLvlType` DURC/DURD)
            // and means "level unknown", so leave altitude nil rather than clamping it to
            // sea level — otherwise the ±band relevance filter would wrongly reject it.
            // `fltlvl` / `altitude` are kept as tolerant fallbacks.
            if let fl = JSONLenient.int(obj["fltLvl"]) ?? JSONLenient.int(obj["fltlvl"])
                ?? JSONLenient.int(obj["altitude"]), fl > 0 {
                p.altitudeFt = fl < 1000 ? fl * 100 : fl
            }
            p.time = JSONLenient.date(obj["obsTime"]) ?? JSONLenient.date(obj["receiptTime"])
            p.aircraftType = JSONLenient.string(obj["acType"]) ?? JSONLenient.string(obj["actype"])
            // Turbulence intensity is a *code string* ("LGT" / "MOD" / "SEV" / "NEG" / ""),
            // not a number, and up to two layers may be reported — take the worse. Fall
            // back to a generic field, then to scraping the raw text.
            let tb1 = turbulence(obj["tbInt1"])
            let tb2 = turbulence(obj["tbInt2"])
            if let worst = [tb1, tb2].compactMap({ $0 }).max() {
                p.turbulence = worst
            } else if let tb = turbulence(obj["turbulence"]) {
                p.turbulence = tb
            } else {
                p.turbulence = TurbulenceSeverity.parse(p.raw)
            }
            p.icing = nonEmpty(obj["icgInt1"]) ?? nonEmpty(obj["icgInt2"]) ?? nonEmpty(obj["icing"])
            return p
        }
    }

    /// Parse a turbulence intensity code, treating empty/whitespace as "not reported".
    private static func turbulence(_ value: Any?) -> TurbulenceSeverity? {
        guard let code = nonEmpty(value) else { return nil }
        return TurbulenceSeverity.parse(code)
    }

    /// The trimmed string value when it is non-empty, else nil (AWC returns `""` for
    /// absent coded fields, which must not be treated as a real value).
    private static func nonEmpty(_ value: Any?) -> String? {
        guard let s = JSONLenient.string(value)?.trimmingCharacters(in: .whitespaces),
              !s.isEmpty else { return nil }
        return s
    }
}
