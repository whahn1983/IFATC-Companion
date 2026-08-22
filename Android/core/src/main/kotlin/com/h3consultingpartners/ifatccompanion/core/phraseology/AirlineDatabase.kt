package com.h3consultingpartners.ifatccompanion.core.phraseology

/**
 * Resolves airline designators to their radiotelephony call names and parses
 * concatenated call signs (e.g. "UA598" or "UAL598") into an airline + flight
 * number pair.
 *
 * Infinite Flight (and the wider world) identify a flight by an airline
 * prefix followed by a flight number. The prefix comes in two flavors:
 *   - ICAO 3-letter designator - "UAL", "DLH", "BAW"
 *   - IATA 2-letter code       - "UA",  "LH",  "BA"
 * Both map to the same spoken telephony name ("United", "Lufthansa",
 * "Speedbird"). This database covers the carriers available in Infinite Flight
 * plus the major world airlines so the automatic call sign resolves for
 * essentially any livery a pilot might fly.
 *
 * Ported from `IFATCCompanion/Phraseology/AirlineDatabase.swift`.
 */
object AirlineDatabase {

    /** The result of splitting a raw call sign into its parts. */
    data class ParsedCallsign(
        /** The matched designator, uppercased (ICAO or IATA), e.g. "UAL" / "UA". */
        val designator: String,
        /** The spoken radio name, e.g. "United". */
        val telephony: String,
        /** The trailing flight number, e.g. "598". */
        val flightNumber: String,
    )

    /**
     * Parse a concatenated call sign such as "UA598" or "UAL598" into an
     * airline + flight number. Returns `null` when the leading letters are not a
     * known airline designator (e.g. a tail number like "N12AB") or there is no
     * numeric flight number, so the caller can fall back to spelling it out.
     */
    fun parse(raw: String): ParsedCallsign? {
        val cleaned = raw
            .trim()
            .replace("-", "")
            .replace(" ", "")
            .uppercase()
        if (cleaned.isEmpty()) return null

        // Leading run of letters is the candidate designator; the rest is the
        // flight number (which must contain at least one digit).
        var split = 0
        while (split < cleaned.length && cleaned[split].isLetter()) split += 1
        val prefix = cleaned.substring(0, split)
        val number = cleaned.substring(split)
        if (prefix.isEmpty() || !number.any { it.isDigit() }) return null

        val telephony = callName(prefix) ?: return null
        return ParsedCallsign(designator = prefix, telephony = telephony, flightNumber = number)
    }

    /**
     * Resolve a designator (ICAO 3-letter or IATA 2-letter) to its spoken
     * telephony name. Returns `null` for unknown designators. The ICAO table is
     * consulted first.
     */
    fun callName(designator: String): String? {
        val key = designator.trim().uppercase()
        if (key.isEmpty()) return null
        icaoCallNames[key]?.let { return it }
        iataCallNames[key]?.let { return it }
        return null
    }

    // MARK: - ICAO 3-letter designators -> telephony name

    val icaoCallNames: Map<String, String> = mapOf(
        // North America — majors & regionals
        "AAL" to "American", "DAL" to "Delta", "UAL" to "United", "SWA" to "Southwest",
        "JBU" to "JetBlue", "ASA" to "Alaska", "NKS" to "Spirit Wings", "FFT" to "Frontier Flight",
        "HAL" to "Hawaiian", "SCX" to "Sun Country", "AAY" to "Allegiant", "VRD" to "Redwood",
        "ASH" to "Air Shuttle", "SKW" to "Skywest", "ENY" to "Envoy", "RPA" to "Brickyard",
        "EDV" to "Endeavor", "JIA" to "Blue Streak", "PDT" to "Piedmont", "QXE" to "Horizon Air",
        "GJS" to "Lakes", "ACA" to "Air Canada", "JZA" to "Jazz", "ROU" to "Rouge",
        "WJA" to "Westjet", "POE" to "Porter", "TSC" to "Air Transat", "CJT" to "Cargojet",
        "AMX" to "Aeromexico", "VOI" to "Volaris", "VIV" to "Aerobus", "AIJ" to "Costera",
        "FDX" to "FedEx", "UPS" to "UPS", "GTI" to "Giant", "ABX" to "Abex",
        "ATN" to "Air Transport", "BOX" to "German Cargo",

        // South America
        "TAM" to "Tam", "GLO" to "Gol Transporte", "AZU" to "Azul", "ARG" to "Argentina",
        "AVA" to "Avianca", "LAN" to "Lan", "LPE" to "Lan Peru", "LXP" to "Lan Express",
        "CMP" to "Copa", "ONE" to "Aero Republica", "TPU" to "Transpac",

        // Europe — majors
        "BAW" to "Speedbird", "VIR" to "Virgin", "EZY" to "Easy", "EXS" to "Channex",
        "RYR" to "Ryanair", "DLH" to "Lufthansa", "CLH" to "Lufthansa Regional", "EWG" to "Eurowings",
        "AFR" to "Airfrans", "KLM" to "KLM", "TRA" to "Transavia", "SAS" to "Scandinavian",
        "IBE" to "Iberia", "IBS" to "Iberia Express", "VLG" to "Vueling", "AEA" to "Europa",
        "AZA" to "Alitalia", "ITY" to "Itarrow", "SWR" to "Swiss", "AUA" to "Austrian",
        "BEL" to "Beeline", "TAP" to "Air Portugal", "FIN" to "Finnair", "NAX" to "Nordic",
        "NSZ" to "Rednose", "NOZ" to "Nordic", "WZZ" to "Wizz Air", "WUK" to "Wizz Go",
        "AYR" to "Aer Lingus", "EIN" to "Shamrock", "LOT" to "Lot", "ROT" to "Tarom",
        "TVF" to "Transavia France", "VKG" to "Viking", "DAT" to "Brussels",
        "BCS" to "Eurotrans", "EVE" to "Evelop", "PGT" to "Sunturk", "CFG" to "Condor",
        "TUI" to "Tomjet", "BER" to "Air Berlin", "GWI" to "German Wings", "SXS" to "Sunexpress",
        "AEE" to "Aegean", "ELY" to "El Al", "ICE" to "Iceair", "MSR" to "Egyptair",
        "RAM" to "Royalair Maroc", "TAR" to "Tunair", "DAH" to "Air Algerie",

        // Middle East
        "UAE" to "Emirates", "QTR" to "Qatari", "ETD" to "Etihad", "GFA" to "Gulf Air",
        "SVA" to "Saudia", "MEA" to "Cedar Jet", "RJA" to "Jordanian", "KAC" to "Kuwaiti",
        "OMA" to "Oman Air", "ABY" to "Arabia", "FAD" to "Fly Adeal", "FDB" to "Skydubai",
        "IRA" to "Iranair", "THY" to "Turkish",

        // Africa
        "ETH" to "Ethiopian", "SAA" to "Springbok", "KQA" to "Kenya", "RWD" to "Rwandair",
        "MWI" to "Air Malawi", "AMU" to "Air Mauritius", "MAU" to "Air Mauritius",

        // Asia — East
        "CCA" to "Air China", "CES" to "China Eastern", "CSN" to "China Southern",
        "CHH" to "Hainan", "CSC" to "Sichuan", "CXA" to "Xiamen Air", "CDG" to "Shandong",
        "CSZ" to "Shenzhen Air", "CBJ" to "Capital Jet", "CQH" to "Spring Air",
        "CHB" to "Lucky Air", "JAL" to "Japan Air", "ANA" to "All Nippon", "APJ" to "Air Peach",
        "JJP" to "Orange Liner", "SKY" to "Skymark", "ADO" to "Air Do", "SNJ" to "Newsky",
        "KAL" to "Koreanair", "AAR" to "Asiana", "JNA" to "Jin Air", "ABL" to "Air Busan",
        "TWB" to "Twayair", "ESR" to "Eastar", "CAL" to "Dynasty", "EVA" to "Eva",
        "TTW" to "Tigerair Taiwan", "SJX" to "Starlux", "UIA" to "Uniair",
        "CPA" to "Cathay", "HDA" to "Dragon", "CRK" to "Bauhinia", "HKE" to "Hongkong Shuttle",

        // Asia — Southeast
        "SIA" to "Singapore", "SLK" to "Silkair", "TGW" to "Go Cat", "MAS" to "Malaysian",
        "AXM" to "Red Cap", "XAX" to "Xanadu", "MXD" to "Express Indo", "BTK" to "Batik",
        "GIA" to "Indonesia", "CTV" to "Citilink", "LNI" to "Lion Inter", "THA" to "Thai",
        "TVJ" to "Thai Vietjet", "AIQ" to "Thai Airasia", "TLM" to "Thai Lion",
        "BKP" to "Bangkok Air", "NOK" to "Nok Air", "VJC" to "Vietjet", "HVN" to "Viet Nam Airlines",
        "BAV" to "Bamboo", "PAL" to "Philippine", "CEB" to "Cebu Air", "APG" to "Aragon",

        // South Asia
        "AIC" to "Air India", "IGO" to "Ifly", "SEJ" to "Spicejet", "VTI" to "Vistara",
        "AKJ" to "Akasa", "PIA" to "Pakistan", "BBC" to "Bangladesh", "ALK" to "Srilankan",

        // Oceania
        "QFA" to "Qantas", "JST" to "Jetstar", "VOZ" to "Velocity", "RXA" to "Regional Express",
        "ANZ" to "New Zealand", "TGG" to "Jetconnect", "FJI" to "Fiji", "ANG" to "Niugini",

        // Cargo / other common
        "GEC" to "Lufthansa Cargo", "CLX" to "Cargolux", "CKS" to "Connie", "MPH" to "Martinair",
        "DHK" to "World Express",
    )

    // MARK: - IATA 2-letter codes -> telephony name

    val iataCallNames: Map<String, String> = mapOf(
        // North America
        "AA" to "American", "DL" to "Delta", "UA" to "United", "WN" to "Southwest",
        "B6" to "JetBlue", "AS" to "Alaska", "NK" to "Spirit Wings", "F9" to "Frontier Flight",
        "HA" to "Hawaiian", "SY" to "Sun Country", "G4" to "Allegiant", "AC" to "Air Canada",
        "WS" to "Westjet", "PD" to "Porter", "TS" to "Air Transat", "AM" to "Aeromexico",
        "Y4" to "Aerobus", "FX" to "FedEx", "5X" to "UPS",

        // South America
        "JJ" to "Tam", "G3" to "Gol Transporte", "AD" to "Azul", "AR" to "Argentina",
        "AV" to "Avianca", "LA" to "Lan", "CM" to "Copa",

        // Europe
        "BA" to "Speedbird", "VS" to "Virgin", "U2" to "Easy", "FR" to "Ryanair",
        "LH" to "Lufthansa", "EW" to "Eurowings", "AF" to "Airfrans", "KL" to "KLM",
        "HV" to "Transavia", "SK" to "Scandinavian", "IB" to "Iberia", "VY" to "Vueling",
        "AZ" to "Itarrow", "LX" to "Swiss", "OS" to "Austrian", "SN" to "Beeline",
        "TP" to "Air Portugal", "AY" to "Finnair", "DY" to "Nordic", "W6" to "Wizz Air",
        "EI" to "Shamrock", "LO" to "Lot", "RO" to "Tarom", "DE" to "Condor",
        "X3" to "Tomjet", "XQ" to "Sunexpress", "A3" to "Aegean", "LY" to "El Al",
        "FI" to "Iceair", "MS" to "Egyptair", "AT" to "Royalair Maroc", "TU" to "Tunair",
        "TK" to "Turkish",

        // Middle East
        "EK" to "Emirates", "QR" to "Qatari", "EY" to "Etihad", "GF" to "Gulf Air",
        "SV" to "Saudia", "ME" to "Cedar Jet", "RJ" to "Jordanian", "KU" to "Kuwaiti",
        "WY" to "Oman Air", "G9" to "Arabia", "FZ" to "Skydubai", "IR" to "Iranair",

        // Africa
        "ET" to "Ethiopian", "SA" to "Springbok", "KQ" to "Kenya", "WB" to "Rwandair",
        "MK" to "Air Mauritius",

        // Asia — East
        "CA" to "Air China", "MU" to "China Eastern", "CZ" to "China Southern",
        "HU" to "Hainan", "3U" to "Sichuan", "MF" to "Xiamen Air", "ZH" to "Shenzhen Air",
        "JL" to "Japan Air", "NH" to "All Nippon", "MM" to "Air Peach", "GK" to "Orange Liner",
        "BC" to "Newsky", "KE" to "Koreanair", "OZ" to "Asiana", "LJ" to "Jin Air",
        "BX" to "Air Busan", "TW" to "Twayair", "ZE" to "Eastar", "CI" to "Dynasty",
        "BR" to "Eva", "JX" to "Starlux", "CX" to "Cathay", "UO" to "Hongkong Shuttle",

        // Asia — Southeast
        "SQ" to "Singapore", "TR" to "Go Cat", "MH" to "Malaysian", "AK" to "Red Cap",
        "D7" to "Xanadu", "QG" to "Express Indo", "ID" to "Batik", "GA" to "Indonesia",
        "QZ" to "Indonesia Airasia", "JT" to "Lion Inter", "TG" to "Thai", "FD" to "Thai Airasia",
        "PG" to "Bangkok Air", "DD" to "Nok Air", "VJ" to "Vietjet", "VN" to "Viet Nam Airlines",
        "QH" to "Bamboo", "PR" to "Philippine", "5J" to "Cebu Air",

        // South Asia
        "AI" to "Air India", "6E" to "Ifly", "SG" to "Spicejet", "UK" to "Vistara",
        "QP" to "Akasa", "PK" to "Pakistan", "BG" to "Bangladesh", "UL" to "Srilankan",

        // Oceania
        "QF" to "Qantas", "JQ" to "Jetstar", "VA" to "Velocity", "ZL" to "Regional Express",
        "NZ" to "New Zealand", "FJ" to "Fiji", "PX" to "Niugini",

        // Cargo
        "LD" to "Air Hong Kong", "CV" to "Cargolux", "RU" to "Volga",
    )
}
