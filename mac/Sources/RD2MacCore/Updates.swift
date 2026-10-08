import Foundation

/// The newest public release, as the GitHub API describes it — the one feed both apps
/// update from (the Android side mirrors this file in `update/UpdateFeed.kt`).
public struct Release: Equatable {
    /// Dotted version, the tag without its leading "v" ("v0.6" → "0.6").
    public let version: String
    public let assetURL: URL
    public let size: Int64
    /// Lowercase hex SHA-256 of the asset, from GitHub's "sha256:<hex>" digest.
    public let sha256: String?
}

public enum UpdateFeed {
    /// GitHub's "latest release": never a draft or a pre-release.
    public static let latestURL = URL(string: "https://api.github.com/repos/esimioni/rd2mac/releases/latest")!
    /// The asset each app installs from.
    public static let macAsset = "RD2Mac.dmg"

    /// The release and its asset named `asset`; nil when the JSON lacks either, or the
    /// release is a draft or a pre-release.
    public static func parse(_ json: Data, asset: String) -> Release? {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard let release = try? decoder.decode(GitHubRelease.self, from: json),
              release.draft != true, release.prerelease != true,
              let file = release.assets.first(where: { $0.name == asset }) else { return nil }
        let version = release.tagName.hasPrefix("v") ? String(release.tagName.dropFirst()) : release.tagName
        return Release(version: version, assetURL: file.browserDownloadUrl, size: file.size,
                       sha256: sha256Hex(file.digest))
    }

    /// "sha256:<64 hex>" → the hex, lowercased; anything else → nil.
    static func sha256Hex(_ digest: String?) -> String? {
        guard let digest, digest.hasPrefix("sha256:") else { return nil }
        let hex = digest.dropFirst("sha256:".count).lowercased()
        return hex.count == 64 && hex.allSatisfy(\.isHexDigit) ? hex : nil
    }

    private struct GitHubRelease: Decodable {
        let tagName: String
        let draft: Bool?
        let prerelease: Bool?
        let assets: [Asset]

        struct Asset: Decodable {
            let name: String
            let size: Int64
            let browserDownloadUrl: URL
            let digest: String?
        }
    }
}

public enum AppVersion {
    /// True when `candidate` is a strictly higher dotted version than `current`
    /// ("0.10" > "0.9", "1.0" > "0.9.9", "0.6" == "0.6.0"). Anything that is not dotted
    /// numbers is never newer: an unreadable tag must not trigger an install.
    public static func isNewer(_ candidate: String, than current: String) -> Bool {
        guard let a = numbers(candidate), let b = numbers(current) else { return false }
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0
            let y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }

    /// Digits only, part by part — `Int("+1")` would let a sign through.
    private static func numbers(_ version: String) -> [Int]? {
        let parts = version.split(separator: ".", omittingEmptySubsequences: false)
        let values = parts.compactMap { part in part.allSatisfy { ("0"..."9").contains($0) } ? Int(part) : nil }
        return values.count == parts.count ? values : nil
    }
}

public enum UpdatePolicy {
    /// Download only a version newer than the installed one that this device has not
    /// already turned down. A release whose asset did not change — the APK reused by a
    /// Mac-only release — would otherwise be fetched and refused every single day.
    public static func wants(_ version: String, installed: String, rejected: String?) -> Bool {
        version != rejected && AppVersion.isNewer(version, than: installed)
    }
}

public enum UpdateSchedule {
    /// One check a day.
    public static let interval: TimeInterval = 24 * 60 * 60

    /// Due when never checked, a day after the last check, or when the clock went back
    /// behind the last check (otherwise a wrong clock could silence updates for good).
    public static func isDue(lastCheck: Date?, now: Date) -> Bool {
        guard let lastCheck else { return true }
        let elapsed = now.timeIntervalSince(lastCheck)
        return elapsed >= interval || elapsed < 0
    }
}
