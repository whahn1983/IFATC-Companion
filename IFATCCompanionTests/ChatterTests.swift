import XCTest
import AVFoundation
@testable import IFATCCompanion

/// Tests for the background radio-chatter generator, the English-voice filter, and the
/// chatter/Live-Activity settings coupling.
final class ChatterTests: XCTestCase {

    /// Deterministic generator so the frequency-bounding assertions are stable.
    private struct SeededRNG: RandomNumberGenerator {
        private var state: UInt64
        init(seed: UInt64) { state = seed == 0 ? 0x9E3779B97F4A7C15 : seed }
        mutating func next() -> UInt64 {
            state ^= state << 13
            state ^= state >> 7
            state ^= state << 17
            return state
        }
    }

    /// Concatenate many exchanges for a facility so keyword assertions are robust to the
    /// per-call randomness. `runwayIdents` seeds the generator's real-runway pool, and
    /// `departures`/`arrivals` the ATIS-active departure/arrival pools.
    private func corpus(for facility: ATCFacility, samples: Int = 80,
                        runwayIdents: [String] = [],
                        departures: [String] = [], arrivals: [String] = []) -> String {
        var gen = ChatterScriptGenerator()
        gen.runwayIdents = runwayIdents
        gen.departureRunwayIdents = departures
        gen.arrivalRunwayIdents = arrivals
        var rng = SeededRNG(seed: 42)
        var text = ""
        for _ in 0..<samples {
            for line in gen.exchange(for: facility, using: &rng) {
                text += " " + line.spokenText.lowercased()
            }
        }
        return text
    }

    // MARK: - Shape

    func testEveryFacilityProducesNonEmptyLines() {
        var gen = ChatterScriptGenerator()
        for facility in ATCFacility.allCases {
            var rng = SeededRNG(seed: 7)
            for _ in 0..<20 {
                let lines = gen.exchange(for: facility, using: &rng)
                XCTAssertFalse(lines.isEmpty, "\(facility) produced no lines")
                for line in lines {
                    XCTAssertFalse(line.spokenText.trimmingCharacters(in: .whitespaces).isEmpty,
                                   "\(facility) produced an empty line")
                }
            }
        }
    }

    func testCallsignsUseRealAirlineNames() {
        let text = corpus(for: .center)
        let anyAirline = ["united", "american", "delta", "southwest", "jetblue",
                          "alaska", "air canada", "fedex"].contains { text.contains($0) }
        XCTAssertTrue(anyAirline, "expected real airline radio names in the chatter")
        // The raw ICAO designators should never be spoken verbatim.
        XCTAssertFalse(text.contains(" ual "))
        XCTAssertFalse(text.contains(" dal "))
    }

    // MARK: - Frequency bounding

    func testCenterWorksEnrouteConceptsNotGroundOrTower() {
        let text = corpus(for: .center)
        let enroute = ["chop", "climb", "descend", "contact", "arrival", "direct"]
            .contains { text.contains($0) }
        XCTAssertTrue(enroute, "Center chatter should be en-route work")
        XCTAssertFalse(text.contains("taxi to runway"), "Center must not issue taxi")
        XCTAssertFalse(text.contains("cleared for takeoff"), "Center must not clear takeoffs")
    }

    func testGroundWorksSurfaceNotTakeoff() {
        let text = corpus(for: .ground)
        let surface = ["taxi", "hold short", "runway"].contains { text.contains($0) }
        XCTAssertTrue(surface, "Ground chatter should be surface movement")
        XCTAssertFalse(text.contains("cleared for takeoff"), "Ground must not clear takeoffs")
        XCTAssertFalse(text.contains("descend via"), "Ground must not descend traffic")
    }

    func testTowerWorksRunwayOperations() {
        let text = corpus(for: .tower)
        let runwayOps = ["cleared for takeoff", "cleared to land", "line up and wait", "final"]
            .contains { text.contains($0) }
        XCTAssertTrue(runwayOps, "Tower chatter should be runway operations")
    }

    func testApproachWorksVectorsAndApproaches() {
        let text = corpus(for: .approach)
        let approachWork = ["heading", "approach", "reduce speed", "tower"].contains { text.contains($0) }
        XCTAssertTrue(approachWork, "Approach chatter should be vectors/approaches")
    }

    func testClearanceIssuesIFRClearances() {
        let text = corpus(for: .clearance)
        XCTAssertTrue(text.contains("cleared to") || text.contains("squawk"),
                      "Clearance chatter should read IFR clearances")
    }

    // MARK: - Real-runway grounding

    /// With a real runway pool supplied (the field's OSM runway ends), the surface- and
    /// runway-working positions must only ever name runways that exist at the field — never a
    /// made-up one like "runway 18" at a field that has only 09/27.
    func testRunwayReferencesUseTheProvidedFieldRunways() {
        let idents = ["09", "27"]
        let allowedSpoken = Set(idents.map { Phonetic.runway($0) })   // "zero niner", "two seven"
        for facility in [ATCFacility.ground, .tower, .approach] {
            let text = corpus(for: facility, runwayIdents: idents)
            // The real runways do get referenced.
            XCTAssertTrue(allowedSpoken.contains { text.contains("runway \($0)") },
                          "\(facility) never referenced a runway from the field's pool")
            // No runway the field doesn't have is ever named.
            for n in 1...36 {
                let spoken = Phonetic.runway(String(format: "%02d", n))
                if allowedSpoken.contains(spoken) { continue }
                XCTAssertFalse(text.contains("runway \(spoken)"),
                               "\(facility) named runway \(n), which isn't at the field")
            }
        }
    }

    /// A single-runway field (both ends in the pool): every Ground runway reference resolves
    /// to that runway's ends, never anything else.
    func testGroundNeverTaxisToARunwayNotAtTheField() {
        let idents = ["16L", "34R"]
        let text = corpus(for: .ground, runwayIdents: idents)
        XCTAssertTrue(text.contains("runway one six left") || text.contains("runway three four right"),
                      "expected the field's real runways in the ground chatter")
        XCTAssertFalse(text.contains("runway one eight"), "named a runway not at the field")
        XCTAssertFalse(text.contains("runway three six"), "named a runway not at the field")
    }

    /// With no pool supplied (no surface loaded yet / no flight plan) the generator keeps its
    /// previous behavior and still produces plausible runway operations.
    func testEmptyRunwayPoolFallsBackToPlausibleRunways() {
        let text = corpus(for: .tower)
        XCTAssertTrue(text.contains("runway"), "tower chatter should still reference runways")
        XCTAssertTrue(["cleared for takeoff", "cleared to land", "line up and wait"]
                        .contains { text.contains($0) },
                      "tower chatter should still work runway operations without a pool")
    }

    // MARK: - ATIS-active departure vs arrival runways

    /// When the ATIS gives distinct departure and arrival runways, Tower clears takeoffs on the
    /// departure runway and landings on the arrival runway — never the other way around.
    func testTowerSplitsTakeoffAndLandingByAtisRunways() {
        let text = corpus(for: .tower, runwayIdents: ["24R", "25R"],
                          departures: ["25R"], arrivals: ["24R"])
        XCTAssertTrue(text.contains("cleared for takeoff runway two five right"),
                      "takeoffs should use the ATIS departure runway")
        XCTAssertFalse(text.contains("cleared for takeoff runway two four right"),
                       "takeoffs must not use the arrival runway")
        XCTAssertTrue(text.contains("cleared to land runway two four right"),
                      "landings should use the ATIS arrival runway")
        XCTAssertFalse(text.contains("cleared to land runway two five right"),
                       "landings must not use the departure runway")
    }

    /// Ground taxis departing traffic to the ATIS departure runway, never the arrival-only one.
    func testGroundUsesTheAtisDepartureRunway() {
        let text = corpus(for: .ground, runwayIdents: ["24R", "25R"],
                          departures: ["25R"], arrivals: ["24R"])
        XCTAssertTrue(text.contains("runway two five right"), "ground should taxi to the departure runway")
        XCTAssertFalse(text.contains("runway two four right"),
                       "ground must not send departing traffic to the arrival-only runway")
    }

    /// Approach clears traffic for the ATIS arrival runway, never the departure-only one.
    func testApproachUsesTheAtisArrivalRunway() {
        let text = corpus(for: .approach, runwayIdents: ["24R", "25R"],
                          departures: ["25R"], arrivals: ["24R"])
        XCTAssertTrue(text.contains("runway two four right"), "approach should use the arrival runway")
        XCTAssertFalse(text.contains("runway two five right"),
                       "approach must not clear an approach to the departure-only runway")
    }

    // MARK: - Voice filtering

    func testEnglishHumanVoicesAreEnglishAndNotNovelty() {
        let voices = VoiceCatalog.englishHumanVoices()
        // Not asserting a non-empty set (a bare CI image might lack voices), but whatever
        // is returned must satisfy the contract.
        for voice in voices {
            XCTAssertTrue(voice.language.hasPrefix("en"), "\(voice.name) is not English")
            XCTAssertFalse(voice.voiceTraits.contains(.isNoveltyVoice), "\(voice.name) is a novelty voice")
            XCTAssertFalse(voice.voiceTraits.contains(.isPersonalVoice), "\(voice.name) is a personal voice")
        }
    }

    func testChatterPoolIsLimitedOrFallsBack() {
        let pool = VoiceCatalog.chatterVoicePool()
        guard !pool.isEmpty else { return } // bare image with no installed voices
        let allAllowed = pool.allSatisfy { VoiceCatalog.allowedChatterVoiceNames.contains($0.name) }
        let allEnglishHuman = pool.allSatisfy(VoiceCatalog.isEnglishHumanVoice)
        XCTAssertTrue(allAllowed || allEnglishHuman,
                      "chatter pool must be the allowed named voices, or the English-human fallback")
    }

    // MARK: - Settings coupling

    func testLiveActivityRequiresChatter() {
        let defaults = UserDefaults(suiteName: "chatter.test.\(UUID().uuidString)")!
        let settings = AppSettings(defaults: defaults)
        XCTAssertFalse(settings.backgroundChatterEnabled)
        XCTAssertFalse(settings.liveActivityEnabled)

        // Enabling the Live Activity turns on the chatter it depends on.
        settings.liveActivityEnabled = true
        XCTAssertTrue(settings.backgroundChatterEnabled)

        // Turning the chatter back off turns the Live Activity off too.
        settings.backgroundChatterEnabled = false
        XCTAssertFalse(settings.liveActivityEnabled)
    }
}
