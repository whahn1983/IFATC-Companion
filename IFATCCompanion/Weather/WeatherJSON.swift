import Foundation

/// Lenient accessors for the loosely-typed JSON the Aviation Weather Center API
/// returns (fields can be numbers, strings, or absent). Keeps parsing resilient.
enum JSONLenient {
    static func array(_ data: Data) -> [[String: Any]] {
        guard let obj = try? JSONSerialization.jsonObject(with: data) else { return [] }
        if let arr = obj as? [[String: Any]] { return arr }
        if let dict = obj as? [String: Any] { return [dict] }
        return []
    }

    static func int(_ value: Any?) -> Int? {
        switch value {
        case let i as Int: return i
        case let d as Double: return Int(d)
        case let s as String:
            let digits = s.filter { $0.isNumber || $0 == "-" }
            return Int(digits)
        default: return nil
        }
    }

    static func double(_ value: Any?) -> Double? {
        switch value {
        case let d as Double: return d
        case let i as Int: return Double(i)
        case let s as String:
            // Handle "10+" or "1/2"
            if s.contains("/") {
                let parts = s.split(separator: "/")
                if parts.count == 2, let a = Double(parts[0]), let b = Double(parts[1]), b != 0 { return a / b }
            }
            return Double(s.filter { $0.isNumber || $0 == "." || $0 == "-" })
        default: return nil
        }
    }

    static func string(_ value: Any?) -> String? {
        switch value {
        case let s as String: return s
        case let i as Int: return String(i)
        case let d as Double: return String(d)
        default: return nil
        }
    }

    /// AWC report times come as ISO-ish strings or epoch seconds.
    static func date(_ value: Any?) -> Date? {
        if let epoch = int(value), epoch > 1_000_000_000 {
            return Date(timeIntervalSince1970: TimeInterval(epoch))
        }
        if let s = string(value) {
            let iso = ISO8601DateFormatter()
            if let d = iso.date(from: s) { return d }
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
            f.timeZone = TimeZone(identifier: "UTC")
            if let d = f.date(from: s) { return d }
        }
        return nil
    }
}
