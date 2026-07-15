import Foundation

/// Pure parsing of the `rd2mac` pull commands' argument tail. Lives in Core (not
/// the CLI target) so it can be unit-tested; the CLI just forwards
/// `Array(CommandLine.arguments.dropFirst(N))`.
///
/// Grammar:
///   pull-screenshot [n]          → count(n)
///   pull [image|video] [n]       → (type, count)
/// A missing/garbage/non-positive count is 1 (never pull "0" or a negative
/// number of items); an unknown media type falls back to `image`.
public enum PullArgs {
    /// The tail after `pull` (e.g. `["image", "3"]`, `["3"]`, `["video"]`, `[]`).
    public static func parse(_ tail: [String]) -> (type: String, count: Int) {
        var type = "image"
        var rest = tail
        if let first = rest.first, first == "image" || first == "video" {
            type = first
            rest = Array(rest.dropFirst())
        }
        return (type, positiveCount(rest.first))
    }

    /// The tail after `pull-screenshot` (e.g. `["3"]` or `[]`).
    public static func count(_ tail: [String]) -> Int {
        positiveCount(tail.first)
    }

    /// A count token → clamped to ≥ 1; nil/garbage/≤0 → 1.
    private static func positiveCount(_ token: String?) -> Int {
        guard let token, let n = Int(token), n >= 1 else { return 1 }
        return n
    }
}
