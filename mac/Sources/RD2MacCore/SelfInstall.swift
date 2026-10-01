import CryptoKit
import Foundation
import Security

/// The pieces of a self-update that touch files and signatures — kept apart from the
/// app's orchestration so each can be tested on a temp directory.
public enum FileDigest {
    /// Lowercase hex SHA-256 of the file, read in chunks (an update image never sits
    /// whole in memory).
    public static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

public enum CodeIdentity {
    /// The running app's designated requirement: for the distributed build, "this
    /// identifier, signed by Developer ID team 57L99SY2N2". Nil when it can't be read.
    public static func runningAppRequirement() -> SecRequirement? {
        var code: SecCode?
        var staticCode: SecStaticCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code,
              SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        return designatedRequirement(of: staticCode)
    }

    /// The designated requirement of the bundle at `url` (tests pin a bundle to itself).
    public static func designatedRequirement(of url: URL) -> SecRequirement? {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &staticCode) == errSecSuccess,
              let staticCode else { return nil }
        return designatedRequirement(of: staticCode)
    }

    /// True when the bundle at `url` is intact — every nested piece included — and
    /// satisfies `requirement`. An update must satisfy the RUNNING app's requirement, so
    /// only a build signed like the installed one gets in; an ad-hoc dev build (whose
    /// requirement is its own hash) never takes an update.
    public static func isValid(_ url: URL, satisfying requirement: SecRequirement) -> Bool {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &staticCode) == errSecSuccess,
              let staticCode else { return false }
        let flags = SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSCheckNestedCode | kSecCSStrictValidate)
        return SecStaticCodeCheckValidity(staticCode, flags, requirement) == errSecSuccess
    }

    private static func designatedRequirement(of code: SecStaticCode) -> SecRequirement? {
        var requirement: SecRequirement?
        guard SecCodeCopyDesignatedRequirement(code, [], &requirement) == errSecSuccess else { return nil }
        return requirement
    }
}

public enum BundleSwap {
    /// Prefix of the old bundle kept beside the new one until the relaunched app deletes it.
    static let leftoverPrefix = ".rd2mac-replaced-"

    /// Puts `new` where `current` is. The running bundle is renamed aside first (a
    /// running app may be renamed), so the new one lands on the same path; when that
    /// move fails, the old bundle goes back. Returns where the old bundle now lives.
    @discardableResult
    public static func replace(_ current: URL, with new: URL, fileManager: FileManager = .default) throws -> URL {
        let aside = current.deletingLastPathComponent()
            .appendingPathComponent(leftoverPrefix + UUID().uuidString + "-" + current.lastPathComponent)
        try fileManager.moveItem(at: current, to: aside)
        do {
            try fileManager.moveItem(at: new, to: current)
        } catch {
            try? fileManager.moveItem(at: aside, to: current)
            throw error
        }
        return aside
    }

    /// Deletes the bundles earlier swaps left beside `bundle`.
    public static func removeLeftovers(beside bundle: URL, fileManager: FileManager = .default) {
        let dir = bundle.deletingLastPathComponent()
        let names = (try? fileManager.contentsOfDirectory(atPath: dir.path)) ?? []
        for name in names where name.hasPrefix(leftoverPrefix) {
            try? fileManager.removeItem(at: dir.appendingPathComponent(name))
        }
    }
}

/// The relaunched app is started while the old one still holds the ports; it waits for
/// that process to exit before binding them.
///
/// The wait must not block the launch: the old app quits only once the system reports
/// the new one launched, and a launch blocked before `NSApplication` runs is never
/// reported — each side waited for the other until the timeout (E2E, 01/10/2026).
public enum RelaunchWait {
    public static let argument = "--rd2mac-wait-pid"

    /// The pid passed after `argument`, if any.
    public static func pid(from arguments: [String]) -> pid_t? {
        guard let i = arguments.firstIndex(of: argument), i + 1 < arguments.count,
              let pid = pid_t(arguments[i + 1]), pid > 0 else { return nil }
        return pid
    }

    /// Calls `then` on the main queue once `pid` is gone, or `timeout` passes — polling,
    /// so the main thread keeps running meanwhile.
    public static func whenExited(_ pid: pid_t, timeout: TimeInterval = 15,
                                  then: @escaping () -> Void) {
        let deadline = Date().addingTimeInterval(timeout)
        func poll() {
            if kill(pid, 0) != 0 || Date() >= deadline {
                then()
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { poll() }
            }
        }
        DispatchQueue.main.async { poll() }
    }
}
