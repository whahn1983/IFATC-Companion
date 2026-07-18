import Foundation

/// The runway-crossing workflow states. Extends the ATC interaction beyond the coarse
/// `ATCState.runwayCrossing` / `.holdingShort` with the fine-grained lifecycle the
/// simulated Ground crossing sequence needs.
enum RunwayCrossingState: String, Codable, Equatable {
    case noCrossingPending
    case crossingDetectedAhead
    case approachingHoldingPosition
    case holdShortInstructionIssued
    case holdingShort
    case crossingClearanceReady
    case crossingClearanceIssued
    case awaitingPilotReadback
    case crossingAuthorized
    case crossingInProgress
    case runwayCenterlineCrossed
    case runwayVacated
    case taxiResumed
    case unauthorizedCrossingDetected
    case lowConfidenceCrossingData

    var title: String {
        switch self {
        case .noCrossingPending: return "No crossing pending"
        case .crossingDetectedAhead: return "Crossing detected ahead"
        case .approachingHoldingPosition: return "Approaching hold short"
        case .holdShortInstructionIssued: return "Hold short instruction issued"
        case .holdingShort: return "Holding short"
        case .crossingClearanceReady: return "Crossing clearance ready"
        case .crossingClearanceIssued: return "Crossing clearance issued"
        case .awaitingPilotReadback: return "Awaiting read back"
        case .crossingAuthorized: return "Crossing authorized"
        case .crossingInProgress: return "Crossing in progress"
        case .runwayCenterlineCrossed: return "Runway centerline crossed"
        case .runwayVacated: return "Runway vacated"
        case .taxiResumed: return "Taxi resumed"
        case .unauthorizedCrossingDetected: return "Unauthorized crossing detected"
        case .lowConfidenceCrossingData: return "Low-confidence crossing data"
        }
    }

    /// Whether the aircraft is currently authorized to be on/entering the runway.
    var isAuthorized: Bool {
        switch self {
        case .crossingAuthorized, .crossingInProgress, .runwayCenterlineCrossed, .runwayVacated, .taxiResumed:
            return true
        default:
            return false
        }
    }

    /// Whether a pilot read-back is outstanding before the crossing can be authorized.
    var awaitingReadback: Bool { self == .awaitingPilotReadback || self == .crossingClearanceIssued }

    /// Whether this state represents an active crossing sequence (map highlights it).
    var isActiveSequence: Bool { self != .noCrossingPending && self != .taxiResumed }
}
