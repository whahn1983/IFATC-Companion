import Foundation

/// Parses METARs from the Aviation Weather Center JSON API and from raw text.
enum METARParser {

    static func parseJSON(_ data: Data) -> [METAR] {
        JSONLenient.array(data).compactMap { obj in
            guard let icao = JSONLenient.string(obj["icaoId"]) ?? JSONLenient.string(obj["station_id"]) else { return nil }
            var m = METAR(icao: icao, raw: JSONLenient.string(obj["rawOb"]) ?? JSONLenient.string(obj["raw_text"]) ?? "")
            m.observationTime = JSONLenient.date(obj["reportTime"]) ?? JSONLenient.date(obj["obsTime"])
            m.windDirection = JSONLenient.int(obj["wdir"])
            m.windSpeed = JSONLenient.int(obj["wspd"])
            m.windGust = JSONLenient.int(obj["wgst"])
            m.visibilitySM = JSONLenient.double(obj["visib"])
            if let altimHpa = JSONLenient.double(obj["altim"]) {
                // AWC reports altimeter in hPa; convert to inHg.
                m.altimeterInHg = altimHpa > 100 ? altimHpa * 0.0295299830714 : altimHpa
            }
            m.temperatureC = JSONLenient.double(obj["temp"])
            m.dewpointC = JSONLenient.double(obj["dewp"])
            m.flightCategory = JSONLenient.string(obj["fltCat"])
            if let clouds = obj["clouds"] as? [[String: Any]] {
                m.clouds = clouds.compactMap { c in
                    guard let cover = JSONLenient.string(c["cover"]) else { return nil }
                    return CloudLayer(cover: cover, baseFt: JSONLenient.int(c["base"]))
                }
            }
            if m.raw.isEmpty == false, m.windDirection == nil {
                // Backfill from raw if structured fields were missing.
                let backfill = parseRaw(m.raw)
                m.windDirection = backfill?.windDirection
                m.windSpeed = backfill?.windSpeed
            }
            return m
        }
    }

    /// Deterministic raw METAR text parser (subset sufficient for ATC phraseology).
    static func parseRaw(_ raw: String) -> METAR? {
        let tokens = raw.split(separator: " ").map(String.init)
        guard tokens.count >= 2 else { return nil }
        var idx = 0
        // Station id (skip optional "METAR"/"SPECI" prefix).
        if tokens[0] == "METAR" || tokens[0] == "SPECI" { idx = 1 }
        guard idx < tokens.count else { return nil }
        let icao = tokens[idx]
        var m = METAR(icao: icao, raw: raw)

        for token in tokens {
            // Wind: dddssKT or dddssGggKT or VRBssKT
            if token.hasSuffix("KT") || token.hasSuffix("MPS") {
                let body = token.replacingOccurrences(of: "KT", with: "").replacingOccurrences(of: "MPS", with: "")
                if body.hasPrefix("VRB") {
                    m.windDirection = 0
                    m.windSpeed = Int(body.dropFirst(3).prefix(2))
                } else if body.count >= 5 {
                    m.windDirection = Int(body.prefix(3))
                    let rest = body.dropFirst(3)
                    if let gIndex = rest.firstIndex(of: "G") {
                        m.windSpeed = Int(rest[rest.startIndex..<gIndex])
                        m.windGust = Int(rest[rest.index(after: gIndex)...])
                    } else {
                        m.windSpeed = Int(rest)
                    }
                }
            } else if token.hasSuffix("SM") {
                let body = token.replacingOccurrences(of: "SM", with: "")
                m.visibilitySM = JSONLenient.double(body)
            } else if token.hasPrefix("A") && token.count == 5, let raw4 = Int(token.dropFirst()) {
                m.altimeterInHg = Double(raw4) / 100.0
            } else if token.hasPrefix("Q") && token.count == 5, let hpa = Int(token.dropFirst()) {
                m.altimeterInHg = Double(hpa) * 0.0295299830714
            } else if isCloudToken(token) {
                let cover = String(token.prefix(3))
                let baseStr = token.dropFirst(3).prefix(3)
                let base = Int(baseStr).map { $0 * 100 }
                m.clouds.append(CloudLayer(cover: cover, baseFt: base))
            } else if token.contains("/") && (token.first == "M" || token.first?.isNumber == true) && token.count <= 7 {
                let parts = token.split(separator: "/")
                if parts.count == 2 {
                    m.temperatureC = decodeTemp(String(parts[0]))
                    m.dewpointC = decodeTemp(String(parts[1]))
                }
            }
        }
        return m
    }

    private static func isCloudToken(_ token: String) -> Bool {
        ["FEW", "SCT", "BKN", "OVC"].contains(where: { token.hasPrefix($0) })
    }

    private static func decodeTemp(_ s: String) -> Double? {
        if s.hasPrefix("M") { return Double(s.dropFirst()).map { -$0 } }
        return Double(s)
    }
}
