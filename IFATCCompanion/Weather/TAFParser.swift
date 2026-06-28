import Foundation

/// Parses TAFs from the Aviation Weather Center JSON API (best-effort).
enum TAFParser {

    static func parseJSON(_ data: Data) -> [TAF] {
        JSONLenient.array(data).compactMap { obj in
            guard let icao = JSONLenient.string(obj["icaoId"]) ?? JSONLenient.string(obj["station_id"]) else { return nil }
            var taf = TAF(icao: icao, raw: JSONLenient.string(obj["rawTAF"]) ?? JSONLenient.string(obj["raw_text"]) ?? "")
            taf.issueTime = JSONLenient.date(obj["issueTime"]) ?? JSONLenient.date(obj["bulletinTime"])
            if let fcsts = obj["fcsts"] as? [[String: Any]] {
                taf.periods = fcsts.map { f in
                    TAFForecastPeriod(
                        raw: JSONLenient.string(f["rawTAF"]) ?? "",
                        windDirection: JSONLenient.int(f["wdir"]),
                        windSpeed: JSONLenient.int(f["wspd"]),
                        visibilitySM: JSONLenient.double(f["visib"]),
                        changeIndicator: JSONLenient.string(f["fcstChange"])
                    )
                }
            }
            return taf
        }
    }
}
