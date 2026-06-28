import Foundation

/// Data types used by the Infinite Flight Connect API v2 manifest.
enum IFDataType: Int {
    case boolean = 0
    case int32 = 1
    case float = 2
    case double = 3
    case string = 4
    case long = 5
    case unknown = -1

    init(raw: Int) { self = IFDataType(rawValue: raw) ?? .unknown }

    var shortName: String {
        switch self {
        case .boolean: return "bool"
        case .int32: return "int"
        case .float: return "float"
        case .double: return "double"
        case .string: return "string"
        case .long: return "long"
        case .unknown: return "?"
        }
    }

    /// Byte length for fixed-width types (nil for variable-length string).
    var byteLength: Int? {
        switch self {
        case .boolean: return 1
        case .int32, .float: return 4
        case .double, .long: return 8
        case .string, .unknown: return nil
        }
    }
}

/// A single entry (state or command) from the Connect manifest.
struct IFManifestEntry: Identifiable, Equatable {
    let id: Int
    let type: IFDataType
    let name: String

    /// Normalised name for keyword matching: lowercased, separators removed.
    var matchKey: String {
        name.lowercased().filter { $0.isLetter || $0.isNumber }
    }
}

/// Parses the raw manifest string returned by Connect v2.
/// Expected per-entry format: `id,type,name` separated by newlines.
enum IFManifestParser {
    static func parse(_ raw: String) -> [IFManifestEntry] {
        var entries: [IFManifestEntry] = []
        let lines = raw.split(whereSeparator: { $0 == "\n" || $0 == "\r" })
        for line in lines {
            let parts = line.split(separator: ",", maxSplits: 2, omittingEmptySubsequences: false)
            guard parts.count >= 3,
                  let id = Int(parts[0].trimmingCharacters(in: .whitespaces)),
                  let typeRaw = Int(parts[1].trimmingCharacters(in: .whitespaces)) else { continue }
            let name = parts[2].trimmingCharacters(in: .whitespaces)
            entries.append(IFManifestEntry(id: id, type: IFDataType(raw: typeRaw), name: name))
        }
        return entries
    }
}
