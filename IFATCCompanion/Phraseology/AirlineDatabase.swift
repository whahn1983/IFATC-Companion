import Foundation

/// Resolves airline designators to their radiotelephony call names and parses
/// concatenated call signs (e.g. "UA598" or "UAL598") into an airline + flight
/// number pair.
///
/// Infinite Flight (and the wider world) identify a flight by an airline
/// prefix followed by a flight number. The prefix comes in two flavors:
///   • ICAO 3-letter designator — "UAL", "DLH", "BAW"
///   • IATA 2-letter code       — "UA",  "LH",  "BA"
/// Both map to the same spoken telephony name ("United", "Lufthansa",
/// "Speedbird"). This database covers the carriers available in Infinite Flight
/// plus the major world airlines so the automatic call sign resolves for
/// essentially any livery a pilot might fly.
enum AirlineDatabase {

    /// The result of splitting a raw call sign into its parts.
    struct ParsedCallsign: Equatable {
        /// The matched designator, uppercased (ICAO or IATA), e.g. "UAL" / "UA".
        let designator: String
        /// The spoken radio name, e.g. "United".
        let telephony: String
        /// The trailing flight number, e.g. "598".
        let flightNumber: String
    }

    /// Parse a concatenated call sign such as "UA598" or "UAL598" into an
    /// airline + flight number. Returns `nil` when the leading letters are not a
    /// known airline designator (e.g. a tail number like "N12AB") or there is no
    /// numeric flight number, so the caller can fall back to spelling it out.
    static func parse(_ raw: String) -> ParsedCallsign? {
        let cleaned = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: " ", with: "")
            .uppercased()
        guard !cleaned.isEmpty else { return nil }

        // Leading run of letters is the candidate designator; the rest is the
        // flight number (which must contain at least one digit).
        let chars = Array(cleaned)
        var split = 0
        while split < chars.count, chars[split].isLetter { split += 1 }
        let prefix = String(chars[0..<split])
        let number = String(chars[split...])
        guard !prefix.isEmpty,
              number.contains(where: { $0.isNumber }) else { return nil }

        guard let telephony = callName(for: prefix) else { return nil }
        return ParsedCallsign(designator: prefix, telephony: telephony, flightNumber: number)
    }

    /// Resolve a designator (ICAO 3-letter or IATA 2-letter) to its spoken
    /// telephony name. Returns `nil` for unknown designators.
    static func callName(for designator: String) -> String? {
        let key = designator.trimmingCharacters(in: .whitespaces).uppercased()
        guard !key.isEmpty else { return nil }
        if let icao = icaoCallNames[key] { return icao }
        if let iata = iataCallNames[key] { return iata }
        return nil
    }

    // MARK: - ICAO 3-letter designators -> telephony name

    static let icaoCallNames: [String: String] = [
        // North America — majors & regionals
        "AAL": "American", "DAL": "Delta", "UAL": "United", "SWA": "Southwest",
        "JBU": "JetBlue", "ASA": "Alaska", "NKS": "Spirit Wings", "FFT": "Frontier Flight",
        "HAL": "Hawaiian", "SCX": "Sun Country", "AAY": "Allegiant", "VRD": "Redwood",
        "ASH": "Air Shuttle", "SKW": "Skywest", "ENY": "Envoy", "RPA": "Brickyard",
        "EDV": "Endeavor", "JIA": "Blue Streak", "PDT": "Piedmont", "QXE": "Horizon Air",
        "GJS": "Lakes", "ACA": "Air Canada", "JZA": "Jazz", "ROU": "Rouge",
        "WJA": "Westjet", "POE": "Porter", "TSC": "Air Transat", "CJT": "Cargojet",
        "AMX": "Aeromexico", "VOI": "Volaris", "VIV": "Aerobus", "AIJ": "Costera",
        "FDX": "FedEx", "UPS": "UPS", "GTI": "Giant", "ABX": "Abex",
        "ATN": "Air Transport", "BOX": "German Cargo",

        // South America
        "TAM": "Tam", "GLO": "Gol Transporte", "AZU": "Azul", "ARG": "Argentina",
        "AVA": "Avianca", "LAN": "Lan", "LPE": "Lan Peru", "LXP": "Lan Express",
        "CMP": "Copa", "ONE": "Aero Republica", "TPU": "Transpac",

        // Europe — majors
        "BAW": "Speedbird", "VIR": "Virgin", "EZY": "Easy", "EXS": "Channex",
        "RYR": "Ryanair", "DLH": "Lufthansa", "CLH": "Lufthansa Regional", "EWG": "Eurowings",
        "AFR": "Airfrans", "KLM": "KLM", "TRA": "Transavia", "SAS": "Scandinavian",
        "IBE": "Iberia", "IBS": "Iberia Express", "VLG": "Vueling", "AEA": "Europa",
        "AZA": "Alitalia", "ITY": "Itarrow", "SWR": "Swiss", "AUA": "Austrian",
        "BEL": "Beeline", "TAP": "Air Portugal", "FIN": "Finnair", "NAX": "Nordic",
        "NSZ": "Rednose", "NOZ": "Nordic", "WZZ": "Wizz Air", "WUK": "Wizz Go",
        "AYR": "Aer Lingus", "EIN": "Shamrock", "LOT": "Lot", "ROT": "Tarom",
        "TVF": "Transavia France", "VKG": "Viking", "DAT": "Brussels",
        "BCS": "Eurotrans", "EVE": "Evelop", "PGT": "Sunturk", "CFG": "Condor",
        "TUI": "Tomjet", "BER": "Air Berlin", "GWI": "German Wings", "SXS": "Sunexpress",
        "AEE": "Aegean", "ELY": "El Al", "ICE": "Iceair", "MSR": "Egyptair",
        "RAM": "Royalair Maroc", "TAR": "Tunair", "DAH": "Air Algerie",

        // Middle East
        "UAE": "Emirates", "QTR": "Qatari", "ETD": "Etihad", "GFA": "Gulf Air",
        "SVA": "Saudia", "MEA": "Cedar Jet", "RJA": "Jordanian", "KAC": "Kuwaiti",
        "OMA": "Oman Air", "ABY": "Arabia", "FAD": "Fly Adeal", "FDB": "Skydubai",
        "IRA": "Iranair", "THY": "Turkish",

        // Africa
        "ETH": "Ethiopian", "SAA": "Springbok", "KQA": "Kenya", "RWD": "Rwandair",
        "MWI": "Air Malawi", "AMU": "Air Mauritius", "MAU": "Air Mauritius",

        // Asia — East
        "CCA": "Air China", "CES": "China Eastern", "CSN": "China Southern",
        "CHH": "Hainan", "CSC": "Sichuan", "CXA": "Xiamen Air", "CDG": "Shandong",
        "CSZ": "Shenzhen Air", "CBJ": "Capital Jet", "CQH": "Spring Air",
        "CHB": "Lucky Air", "JAL": "Japan Air", "ANA": "All Nippon", "APJ": "Air Peach",
        "JJP": "Orange Liner", "SKY": "Skymark", "ADO": "Air Do", "SNJ": "Newsky",
        "KAL": "Koreanair", "AAR": "Asiana", "JNA": "Jin Air", "ABL": "Air Busan",
        "TWB": "Twayair", "ESR": "Eastar", "CAL": "Dynasty", "EVA": "Eva",
        "TTW": "Tigerair Taiwan", "SJX": "Starlux", "UIA": "Uniair",
        "CPA": "Cathay", "HDA": "Dragon", "CRK": "Bauhinia", "HKE": "Hongkong Shuttle",

        // Asia — Southeast
        "SIA": "Singapore", "SLK": "Silkair", "TGW": "Go Cat", "MAS": "Malaysian",
        "AXM": "Red Cap", "XAX": "Xanadu", "MXD": "Express Indo", "BTK": "Batik",
        "GIA": "Indonesia", "CTV": "Citilink", "LNI": "Lion Inter", "THA": "Thai",
        "TVJ": "Thai Vietjet", "AIQ": "Thai Airasia", "TLM": "Thai Lion",
        "BKP": "Bangkok Air", "NOK": "Nok Air", "VJC": "Vietjet", "HVN": "Viet Nam Airlines",
        "BAV": "Bamboo", "PAL": "Philippine", "CEB": "Cebu Air", "APG": "Aragon",

        // South Asia
        "AIC": "Air India", "IGO": "Ifly", "SEJ": "Spicejet", "VTI": "Vistara",
        "AKJ": "Akasa", "PIA": "Pakistan", "BBC": "Bangladesh", "ALK": "Srilankan",

        // Oceania
        "QFA": "Qantas", "JST": "Jetstar", "VOZ": "Velocity", "RXA": "Regional Express",
        "ANZ": "New Zealand", "TGG": "Jetconnect", "FJI": "Fiji", "ANG": "Niugini",

        // Cargo / other common
        "GEC": "Lufthansa Cargo", "CLX": "Cargolux", "CKS": "Connie", "MPH": "Martinair",
        "DHK": "World Express",
    ]

    // MARK: - IATA 2-letter codes -> telephony name

    static let iataCallNames: [String: String] = [
        // North America
        "AA": "American", "DL": "Delta", "UA": "United", "WN": "Southwest",
        "B6": "JetBlue", "AS": "Alaska", "NK": "Spirit Wings", "F9": "Frontier Flight",
        "HA": "Hawaiian", "SY": "Sun Country", "G4": "Allegiant", "AC": "Air Canada",
        "WS": "Westjet", "PD": "Porter", "TS": "Air Transat", "AM": "Aeromexico",
        "Y4": "Aerobus", "FX": "FedEx", "5X": "UPS",

        // South America
        "JJ": "Tam", "G3": "Gol Transporte", "AD": "Azul", "AR": "Argentina",
        "AV": "Avianca", "LA": "Lan", "CM": "Copa",

        // Europe
        "BA": "Speedbird", "VS": "Virgin", "U2": "Easy", "FR": "Ryanair",
        "LH": "Lufthansa", "EW": "Eurowings", "AF": "Airfrans", "KL": "KLM",
        "HV": "Transavia", "SK": "Scandinavian", "IB": "Iberia", "VY": "Vueling",
        "AZ": "Itarrow", "LX": "Swiss", "OS": "Austrian", "SN": "Beeline",
        "TP": "Air Portugal", "AY": "Finnair", "DY": "Nordic", "W6": "Wizz Air",
        "EI": "Shamrock", "LO": "Lot", "RO": "Tarom", "DE": "Condor",
        "X3": "Tomjet", "XQ": "Sunexpress", "A3": "Aegean", "LY": "El Al",
        "FI": "Iceair", "MS": "Egyptair", "AT": "Royalair Maroc", "TU": "Tunair",
        "TK": "Turkish",

        // Middle East
        "EK": "Emirates", "QR": "Qatari", "EY": "Etihad", "GF": "Gulf Air",
        "SV": "Saudia", "ME": "Cedar Jet", "RJ": "Jordanian", "KU": "Kuwaiti",
        "WY": "Oman Air", "G9": "Arabia", "FZ": "Skydubai", "IR": "Iranair",

        // Africa
        "ET": "Ethiopian", "SA": "Springbok", "KQ": "Kenya", "WB": "Rwandair",
        "MK": "Air Mauritius",

        // Asia — East
        "CA": "Air China", "MU": "China Eastern", "CZ": "China Southern",
        "HU": "Hainan", "3U": "Sichuan", "MF": "Xiamen Air", "ZH": "Shenzhen Air",
        "JL": "Japan Air", "NH": "All Nippon", "MM": "Air Peach", "GK": "Orange Liner",
        "BC": "Newsky", "KE": "Koreanair", "OZ": "Asiana", "LJ": "Jin Air",
        "BX": "Air Busan", "TW": "Twayair", "ZE": "Eastar", "CI": "Dynasty",
        "BR": "Eva", "JX": "Starlux", "CX": "Cathay", "UO": "Hongkong Shuttle",

        // Asia — Southeast
        "SQ": "Singapore", "TR": "Go Cat", "MH": "Malaysian", "AK": "Red Cap",
        "D7": "Xanadu", "QG": "Express Indo", "ID": "Batik", "GA": "Indonesia",
        "QZ": "Indonesia Airasia", "JT": "Lion Inter", "TG": "Thai", "FD": "Thai Airasia",
        "PG": "Bangkok Air", "DD": "Nok Air", "VJ": "Vietjet", "VN": "Viet Nam Airlines",
        "QH": "Bamboo", "PR": "Philippine", "5J": "Cebu Air",

        // South Asia
        "AI": "Air India", "6E": "Ifly", "SG": "Spicejet", "UK": "Vistara",
        "QP": "Akasa", "PK": "Pakistan", "BG": "Bangladesh", "UL": "Srilankan",

        // Oceania
        "QF": "Qantas", "JQ": "Jetstar", "VA": "Velocity", "ZL": "Regional Express",
        "NZ": "New Zealand", "FJ": "Fiji", "PX": "Niugini",

        // Cargo
        "LD": "Air Hong Kong", "CV": "Cargolux", "RU": "Volga",
    ]
}
