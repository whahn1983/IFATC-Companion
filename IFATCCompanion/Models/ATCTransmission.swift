import Foundation

/// A single line in the ATC transcript — from a simulated controller or the pilot.
struct ATCTransmission: Identifiable, Equatable, Codable {
    enum Sender: String, Codable {
        case atc
        case pilot
        case system
    }

    /// The pilot read-back that matches *this* controller call, composed when the
    /// call is built (the companion knows exactly what it said). Lets the Read Back
    /// button echo the actual last message — including frequency hand-offs and
    /// vectors — instead of re-deriving a read-back from the conversational state.
    struct Readback: Equatable, Codable {
        var displayText: String
        var spokenText: String
        /// Facility the read-back is addressed to / spoken on.
        var facility: ATCFacility
        /// When the call is a frequency hand-off, the facility to auto-tune to once
        /// the pilot has read it back ("contacting Tower on 118.3" → switch to Tower).
        var tuneTo: ATCFacility?
    }

    var id = UUID()
    var sender: Sender
    var facility: ATCFacility
    /// Human-readable text shown in the transcript (normal digits).
    var displayText: String
    /// Phonetic text passed to the speech synthesizer ("niner", "flight level…").
    var spokenText: String
    var timestamp: Date
    /// Optional precomposed pilot read-back for this controller call.
    var readback: Readback?
    /// True for a one-way ATIS broadcast line. It is spoken on the dedicated ATIS
    /// voice and is never treated as a controller instruction (no read-back, no
    /// hand-off bookkeeping). Optional (rather than a defaulted `Bool`) so transcripts
    /// persisted before this field decode cleanly — the synthesized `Decodable` treats a
    /// missing key as `nil`. Read it via `isATISLine`, which maps `nil`/`false` alike.
    var isATIS: Bool?

    /// Whether this line is an ATIS broadcast (nil and false read as "no").
    var isATISLine: Bool { isATIS == true }

    init(sender: Sender,
         facility: ATCFacility,
         displayText: String,
         spokenText: String? = nil,
         timestamp: Date = Date(),
         readback: Readback? = nil,
         isATIS: Bool? = nil) {
        self.sender = sender
        self.facility = facility
        self.displayText = displayText
        self.spokenText = spokenText ?? displayText
        self.timestamp = timestamp
        self.readback = readback
        self.isATIS = isATIS
    }

    static func == (lhs: ATCTransmission, rhs: ATCTransmission) -> Bool {
        lhs.id == rhs.id
    }

    /// View a composed pilot transmission's text as a `Readback` payload that can be
    /// attached to the controller call it answers.
    func asReadback(facility: ATCFacility, tuneTo: ATCFacility? = nil) -> Readback {
        Readback(displayText: displayText, spokenText: spokenText, facility: facility, tuneTo: tuneTo)
    }
}
