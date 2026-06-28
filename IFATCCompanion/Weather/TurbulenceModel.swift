import Foundation

/// A composite ride-quality assessment: a continuous index plus the discrete
/// severity it maps to and the human-readable factors that drove it.
struct RideAssessment: Equatable {
    /// 0 (smooth) … 1 (severe).
    var index: Double
    var severity: TurbulenceSeverity
    var contributors: [String]

    static let smooth = RideAssessment(index: 0, severity: .smooth, contributors: [])
}

/// A more sophisticated, still fully deterministic ride-quality model. It blends
/// multiple signals — route PIREPs (weighted by distance ahead and report age),
/// SIGMET turbulence/convective advisories, and a low-level wind-shear proxy from
/// the surface METAR — into a single ride index and severity. No AI.
struct TurbulenceModel {

    struct Config {
        /// Distance (NM ahead) at which a PIREP's weight halves.
        var distanceHalfLifeNM: Double = 120
        /// Report age (minutes) at which a PIREP's weight halves.
        var ageHalfLifeMin: Double = 90
        /// Below this altitude (ft MSL) surface wind shear is considered relevant.
        var lowLevelCeilingFt: Double = 10000
    }

    var config = Config()

    /// Produce an overall ride assessment for the current position/altitude.
    func assess(items: [RideReportItem],
                sigmets: [SIGMET] = [],
                metar: METAR? = nil,
                altitudeFt: Double) -> RideAssessment {
        var index = 0.0
        var contributors: [String] = []

        // 1. PIREP contribution — take the strongest distance/age-weighted item.
        var bestPirep = 0.0
        for item in items {
            let score = weightedScore(for: item)
            if score > bestPirep { bestPirep = score }
        }
        if bestPirep > 0 {
            index = max(index, bestPirep)
            contributors.append("pilot reports")
        }

        // 2. SIGMET contribution — turbulence/convective advisories raise the floor.
        if let sigmetBump = sigmetContribution(sigmets) {
            index = max(index, sigmetBump.value)
            contributors.append(sigmetBump.label)
        }

        // 3. Low-level wind shear proxy from the surface METAR.
        if altitudeFt <= config.lowLevelCeilingFt, let shear = windShearContribution(metar) {
            // Additive but capped: shear compounds existing turbulence near the ground.
            index = min(1.0, index + shear.value)
            contributors.append(shear.label)
        }

        index = min(1.0, max(0.0, index))
        return RideAssessment(index: index, severity: severity(for: index), contributors: contributors)
    }

    // MARK: - Components

    /// Distance- and age-weighted severity fraction (0…1) for a single PIREP item.
    func weightedScore(for item: RideReportItem) -> Double {
        let severityFraction = Double(item.severity.rawValue) / Double(TurbulenceSeverity.severe.rawValue)
        let distance = item.distanceAheadNM ?? 0
        let distanceWeight = pow(0.5, max(0, distance) / config.distanceHalfLifeNM)
        let ageWeight: Double
        if let age = item.ageMinutes {
            ageWeight = pow(0.5, max(0, age) / config.ageHalfLifeMin)
        } else {
            ageWeight = 1.0
        }
        return severityFraction * distanceWeight * ageWeight
    }

    private func sigmetContribution(_ sigmets: [SIGMET]) -> (value: Double, label: String)? {
        var value = 0.0
        var label: String?
        for sigmet in sigmets {
            let hazard = (sigmet.hazard ?? sigmet.raw).uppercased()
            if hazard.contains("CONV") || hazard.contains("TS") {
                value = max(value, 0.8); label = "convective SIGMET"
            } else if hazard.contains("TURB") {
                value = max(value, 0.6); label = "turbulence SIGMET"
            } else if hazard.contains("ICE") || hazard.contains("MTW") {
                value = max(value, 0.45); label = "SIGMET advisory"
            }
        }
        return label.map { (value, $0) }
    }

    private func windShearContribution(_ metar: METAR?) -> (value: Double, label: String)? {
        guard let m = metar else { return nil }
        let speed = m.windSpeed ?? 0
        let gust = m.windGust ?? speed
        let spread = max(0, gust - speed)
        var value = 0.0
        if spread >= 10 { value += 0.3 } else if spread >= 6 { value += 0.15 }
        if speed >= 25 { value += 0.2 } else if speed >= 18 { value += 0.1 }
        guard value > 0 else { return nil }
        return (min(0.4, value), "surface wind shear")
    }

    /// Map a continuous index onto the discrete severity scale.
    func severity(for index: Double) -> TurbulenceSeverity {
        switch index {
        case ..<0.15: return .smooth
        case ..<0.35: return .lightChop
        case ..<0.55: return .light
        case ..<0.80: return .moderate
        default: return .severe
        }
    }
}
