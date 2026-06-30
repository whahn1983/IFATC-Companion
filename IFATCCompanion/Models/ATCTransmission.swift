import Foundation

/// A single line in the ATC transcript — from a simulated controller or the pilot.
struct ATCTransmission: Identifiable, Equatable, Codable {
    enum Sender: String, Codable {
        case atc
        case pilot
        case system
    }

    var id = UUID()
    var sender: Sender
    var facility: ATCFacility
    /// Human-readable text shown in the transcript (normal digits).
    var displayText: String
    /// Phonetic text passed to the speech synthesizer ("niner", "flight level…").
    var spokenText: String
    var timestamp: Date

    init(sender: Sender,
         facility: ATCFacility,
         displayText: String,
         spokenText: String? = nil,
         timestamp: Date = Date()) {
        self.sender = sender
        self.facility = facility
        self.displayText = displayText
        self.spokenText = spokenText ?? displayText
        self.timestamp = timestamp
    }

    static func == (lhs: ATCTransmission, rhs: ATCTransmission) -> Bool {
        lhs.id == rhs.id
    }
}
