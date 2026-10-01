import AppKit
import RD2MacCore

/// Self-update, checked once a day against the public GitHub release (Eduardo,
/// 01/10/2026). A newer DMG is downloaded and checked against GitHub's digest, the app
/// is copied out of it and accepted only when it is intact and signed like the running
/// app (same identifier, same Developer ID team). Then, once no transfer has moved for
/// a while, the bundle is swapped in place and the new copy relaunched — it waits for
/// this process to exit before taking the ports.
@MainActor
final class Updater {
    private static let lastCheckKey = "updateLastCheck"
    /// A release whose app turned out unusable here (not newer, not signed like us).
    private static let rejectedKey = "updateRejectedVersion"
    /// Test seam for a local feed: `defaults write net.simioni.rd2mac updateFeedURL <url>`.
    private static let feedOverrideKey = "updateFeedURL"
    private static let tickInterval: TimeInterval = 60 * 60
    private static let quietRetry: TimeInterval = 60

    private let defaults = UserDefaults.standard
    private var timer: Timer?
    private var checking = false
    private var waitingForQuiet = false
    private var staged: URL?

    private var bundleURL: URL { Bundle.main.bundleURL }
    private var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }
    private var workDir: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("net.simioni.rd2mac/update", isDirectory: true)
    }

    func start() {
        BundleSwap.removeLeftovers(beside: bundleURL)   // the bundle a previous update replaced
        timer = Timer.scheduledTimer(withTimeInterval: Self.tickInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        // Not at launch itself: let discovery and the servers settle first.
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in self?.tick() }
    }

    private func tick() {
        if let staged {
            installWhenQuiet(staged)
            return
        }
        guard !checking,
              UpdateSchedule.isDue(lastCheck: defaults.object(forKey: Self.lastCheckKey) as? Date,
                                   now: Date()) else { return }
        checking = true
        Task {
            defer { checking = false }
            do {
                if let app = try await stageNewerApp() {
                    staged = app
                    installWhenQuiet(app)
                }
            } catch {
                NSLog("RD2Mac update: \(error.localizedDescription)")
            }
        }
    }

    /// The newer app, copied out of the release DMG and verified; nil when this copy is
    /// already current or can't replace itself where it lives.
    private func stageNewerApp() async throws -> URL? {
        let feedURL = defaults.string(forKey: Self.feedOverrideKey).flatMap(URL.init(string:))
            ?? UpdateFeed.latestURL
        var request = URLRequest(url: feedURL)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (json, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let release = UpdateFeed.parse(json, asset: UpdateFeed.macAsset) else {
            throw UpdateError.feed
        }
        defaults.set(Date(), forKey: Self.lastCheckKey)
        guard UpdatePolicy.wants(release.version, installed: currentVersion,
                                 rejected: defaults.string(forKey: Self.rejectedKey)) else { return nil }
        guard FileManager.default.isWritableFile(atPath: bundleURL.deletingLastPathComponent().path) else {
            throw UpdateError.notWritable(bundleURL.deletingLastPathComponent().path)
        }
        guard let requirement = CodeIdentity.runningAppRequirement() else { throw UpdateError.signature }

        try? FileManager.default.removeItem(at: workDir)
        try FileManager.default.createDirectory(at: workDir, withIntermediateDirectories: true)
        let dmg = workDir.appendingPathComponent(UpdateFeed.macAsset)
        let (downloaded, _) = try await URLSession.shared.download(from: release.assetURL)
        try FileManager.default.moveItem(at: downloaded, to: dmg)
        let size = (try FileManager.default.attributesOfItem(atPath: dmg.path)[.size] as? NSNumber)?.int64Value ?? -1
        guard size == release.size else { throw UpdateError.size(size, expected: release.size) }
        if let expected = release.sha256, try FileDigest.sha256(of: dmg) != expected {
            throw UpdateError.digest
        }

        let app = try copyApp(outOf: dmg)
        let stagedVersion = Bundle(url: app)?.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        // Both verdicts are final for this release: remember them, don't fetch it daily.
        guard CodeIdentity.isValid(app, satisfying: requirement) else {
            defaults.set(release.version, forKey: Self.rejectedKey)
            throw UpdateError.signature
        }
        guard let stagedVersion, AppVersion.isNewer(stagedVersion, than: currentVersion) else {
            defaults.set(release.version, forKey: Self.rejectedKey)
            throw UpdateError.version
        }
        return app
    }

    /// Mounts the DMG read-only, copies the app out (quarantine dropped: it is our own
    /// download, verified below) and detaches.
    private func copyApp(outOf dmg: URL) throws -> URL {
        let mount = workDir.appendingPathComponent("mount", isDirectory: true)
        try FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
        try run("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-noautoopen",
                                     "-mountpoint", mount.path, dmg.path])
        defer { try? run("/usr/bin/hdiutil", ["detach", mount.path, "-force"]) }
        let app = workDir.appendingPathComponent(bundleURL.lastPathComponent)
        try run("/usr/bin/ditto", ["--noqtn", mount.appendingPathComponent(bundleURL.lastPathComponent).path,
                                   app.path])
        return app
    }

    /// Swaps the bundle once nothing is transferring, relaunches the new copy, quits.
    private func installWhenQuiet(_ app: URL) {
        guard !waitingForQuiet else { return }
        guard TransferActivity.shared.isQuiet else {
            waitingForQuiet = true
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.quietRetry) { [weak self] in
                self?.waitingForQuiet = false
                self?.installWhenQuiet(app)
            }
            return
        }
        staged = nil
        do {
            try BundleSwap.replace(bundleURL, with: app)
        } catch {
            NSLog("RD2Mac update: swap failed: \(error.localizedDescription)")
            return
        }
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        configuration.arguments = [RelaunchWait.argument, String(getpid())]
        NSWorkspace.shared.openApplication(at: bundleURL, configuration: configuration) { _, error in
            if let error {
                // The new bundle is in place: the next launch runs it.
                NSLog("RD2Mac update: relaunch failed: \(error.localizedDescription)")
                return
            }
            DispatchQueue.main.async { NSApp.terminate(nil) }
        }
    }

    private func run(_ tool: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw UpdateError.tool(tool, process.terminationStatus) }
    }

    enum UpdateError: LocalizedError {
        case feed, digest, signature, version
        case size(Int64, expected: Int64)
        case notWritable(String)
        case tool(String, Int32)

        var errorDescription: String? {
            switch self {
            case .feed: return "no readable release in the feed"
            case .size(let size, let expected): return "download is \(size) B, the release says \(expected)"
            case .digest: return "download's SHA-256 differs from the release's"
            case .signature: return "update is not signed like the running app"
            case .version: return "the DMG's app is not newer than this one"
            case .notWritable(let dir): return "can't replace the app in \(dir) (not writable)"
            case .tool(let tool, let status): return "\(tool) exited \(status)"
            }
        }
    }
}
