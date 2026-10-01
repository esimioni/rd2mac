import Cocoa
import FinderSync

/// RD2Mac Finder Sync extension: puts "Send to Phone" at the TOP LEVEL of the
/// Finder contextual menu. The Share Extension still exists (Share submenu, and
/// it works from any app); this one exists purely to remove the two extra clicks
/// in Finder — a FinderSync extension is the only Apple API that reaches the
/// root of the right-click menu.
///
/// It never reads the selected files: it hands their PATHS to the main app's
/// loopback control endpoint (`POST /send?path=`), which is unsandboxed and does
/// the reading + the encrypted transfer. So this extension needs `network.client`
/// and no file access at all.
///
/// @objc name is stable ("FinderController") → matches NSExtensionPrincipalClass.
@objc(FinderController)
final class FinderController: FIFinderSync {

    private static let controlPort = 53319   // mirrors RD2Mac.controlPort
    private static let workTimeout: TimeInterval = 24 * 60 * 60   // mirrors ControlClient.workTimeout

    override init() {
        super.init()
        // Watch the whole volume — the menu must appear for a file anywhere.
        // We publish no badges/sync state, so the broad root only costs being loaded.
        FIFinderSyncController.default().directoryURLs = [URL(fileURLWithPath: "/")]
    }

    // MARK: - Menu

    override func menu(for menuKind: FIMenuKind) -> NSMenu {
        let menu = NSMenu(title: "")
        guard menuKind == .contextualMenuForItems, !Self.selectedFiles().isEmpty else { return menu }
        let item = NSMenuItem(title: Self.l("finder.send"),
                              action: #selector(sendToPhone(_:)), keyEquivalent: "")
        item.image = Self.menuIcon()
        menu.addItem(item)
        return menu
    }

    /// The menu is built here but drawn by the Finder, and the template flag does
    /// NOT survive the trip — a template symbol arrives as plain black art, which
    /// disappears into a dark-mode menu. So tint it ourselves: white in dark mode,
    /// the default black otherwise. `menu(for:)` runs on every right-click, so a
    /// theme switch is picked up with no restart.
    private static func menuIcon() -> NSImage? {
        guard let symbol = NSImage(systemSymbolName: "iphone", accessibilityDescription: nil) else {
            return nil
        }
        symbol.isTemplate = true
        guard isDarkMode else { return symbol }
        let tinted = NSImage(size: symbol.size, flipped: false) { rect in
            symbol.draw(in: rect)
            NSColor.white.set()
            rect.fill(using: .sourceAtop)
            return true
        }
        tinted.isTemplate = false
        return tinted
    }

    /// Read the system-wide appearance: inside an app extension the app's own
    /// defaults are its container's, so ask the global domain directly.
    private static var isDarkMode: Bool {
        (CFPreferencesCopyAppValue("AppleInterfaceStyle" as CFString,
                                   kCFPreferencesAnyApplication) as? String)?
            .lowercased().hasPrefix("dark") ?? false
    }

    /// Selected regular files. Folders are dropped: `/send` transfers one file's
    /// bytes, so a directory would only produce a failure — better to not offer it.
    private static func selectedFiles() -> [URL] {
        (FIFinderSyncController.default().selectedItemURLs() ?? []).filter { url in
            var isDir: ObjCBool = false
            let exists = FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir)
            return exists && !isDir.boolValue
        }
    }

    @objc func sendToPhone(_ sender: AnyObject?) {
        let files = Self.selectedFiles()
        DispatchQueue.global(qos: .userInitiated).async {
            var failures: [String] = []
            for url in files {
                if let error = Self.send(path: url.path) {
                    failures.append("\(url.lastPathComponent): \(error)")
                }
            }
            DispatchQueue.main.async {
                if failures.isEmpty {
                    NSSound(named: "Glass")?.play()
                } else {
                    NSSound(named: "Basso")?.play()   // audible even if the alert can't show
                    Self.alert(failures)
                }
            }
        }
    }

    // MARK: - Transfer

    /// POST the local path to the running app. Returns nil on success, else the
    /// error text (the control endpoint answers 404 when no phone is discovered).
    private static func send(path: String) -> String? {
        let url = URL(string: "http://127.0.0.1:\(controlPort)\(sendTarget(forPath: path))")!
        var request = URLRequest(url: url, timeoutInterval: workTimeout)
        request.httpMethod = "POST"
        let semaphore = DispatchSemaphore(value: 0)
        var failure: String? = "no response"
        URLSession.shared.dataTask(with: request) { data, response, error in
            defer { semaphore.signal() }
            if let error { failure = error.localizedDescription; return }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            failure = status == 200 ? nil
                : (data.flatMap { String(data: $0, encoding: .utf8) }?.trimmed ?? "HTTP \(status)")
        }.resume()
        semaphore.wait()
        return failure
    }

    /// Mirror of `ControlService.sendTarget(forPath:)` — duplicated so the
    /// extension stays a standalone single-file build (same rule as the Share
    /// Extension's `headerEncode`). Keep both in sync.
    private static func sendTarget(forPath path: String) -> String {
        let unreserved = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let encoded = path.addingPercentEncoding(withAllowedCharacters: unreserved) ?? path
        return "/send?path=\(encoded)"
    }

    // MARK: - Feedback

    private static func alert(_ failures: [String]) {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = l("finder.err_title")
        alert.informativeText = failures.joined(separator: "\n")
        alert.addButton(withTitle: l("button.ok"))
        alert.runModal()
    }

    private static func l(_ key: String) -> String {
        Bundle.main.localizedString(forKey: key, value: key, table: nil)
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
