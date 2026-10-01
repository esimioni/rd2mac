import AppKit
import CryptoKit
import RD2MacCore
import ServiceManagement

/// Short helper for a localized menu string (en / pt-BR bundles in Resources).
private func L(_ key: String) -> String { NSLocalizedString(key, comment: "") }

/// Handles the "Send to Phone with RD2Mac" entry in the system Services menu
/// (declared under NSServices in Info.plist). Reads the file URLs from the
/// pasteboard and POSTs each to the app's own loopback control endpoint —
/// the exact path the CLI and the Share Extension already use, so retries and
/// the no-phantom-send guarantee come for free.
final class ServiceProvider: NSObject {
    /// (sent, failed) — delivered on the main queue after each service call.
    var onResult: ((Int, Int) -> Void)?

    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?,
                           error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let options: [NSPasteboard.ReadingOptionKey: Any] = [.urlReadingFileURLsOnly: true]
        let urls = (pboard.readObjects(forClasses: [NSURL.self], options: options) as? [URL]) ?? []
        guard !urls.isEmpty else {
            error.pointee = "RD2Mac: no files in the selection" as NSString
            return
        }
        // Off the main thread: sends can take seconds and services arrive on main.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var sent = 0, failed = 0
            for url in urls {
                let target = ControlService.sendTarget(forPath: url.path)
                var request = URLRequest(url: URL(string: "http://127.0.0.1:\(RD2Mac.controlPort)\(target)")!)
                request.httpMethod = "POST"
                request.timeoutInterval = ControlClient.workTimeout // the app answers when the send is over
                let semaphore = DispatchSemaphore(value: 0)
                var ok = false
                URLSession.shared.dataTask(with: request) { _, response, _ in
                    ok = (response as? HTTPURLResponse)?.statusCode == 200
                    semaphore.signal()
                }.resume()
                semaphore.wait()
                ok ? (sent += 1) : (failed += 1)
            }
            DispatchQueue.main.async {
                // Same audible result as the Finder extension: the menu-bar flash
                // is easy to miss, and third-party file managers (QSpace) surface
                // this Services item at the top level, where it's the only feedback.
                NSSound(named: failed == 0 ? "Glass" : "Basso")?.play()
                self?.onResult?(sent, failed)
            }
        }
    }
}

/// RD2Mac menu-bar agent. Runs the LAN HTTP server + Bonjour discovery, the
/// loopback control server for the CLI/Share Extension/Services menu, pairing
/// with SAS confirmation, and the clipboard sync endpoints.
final class AppController: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var statusItem: NSStatusItem!
    /// One persistent menu, refilled in place — replacing `statusItem.menu` per
    /// rebuild would prevent the open-time refresh in `menuNeedsUpdate`.
    private let menu = NSMenu()
    private var server: HTTPServer!
    private var controlServer: HTTPServer!
    private var controlService: ControlService!
    private let advertiser = PeerAdvertiser()
    private var browser: PeerBrowser!
    private let pairing = PairingStore()
    private var livePeers: [ResolvedPeer] = []
    /// Set when the LAN server failed to bind (port taken) — surfaced in the menu.
    private var serverError: String?
    /// changeCount produced by the phone's own POST /clipboard; never served back
    /// (ClipboardEchoGuard) or the phone would loop on its own content.
    private var lastInboundChangeCount: Int?
    /// A SAS alert is on screen; a newer incoming pairing aborts it (its session
    /// is already stale — confirming it would save a key the peer no longer has).
    private var sasAlertVisible = false
    /// Last proximity-gate status the phone reported, with its arrival time: the
    /// phone keeps a report alive every ~60 s, so silence past 3× that means the
    /// information is stale (phone gone / service dead) and the row disappears.
    private var phoneProximity: ProximityReport?
    private var phoneProximityAt = Date.distantPast
    private let proximityStaleAfter: TimeInterval = 180
    private var flashReset: DispatchWorkItem?
    /// The phone we actually talk to: the paired one, if it's on the network now.
    private var activePeer: ResolvedPeer? {
        PeerSelection.activePeer(from: livePeers, pairedId: pairing.pairedPeer?.id)
    }

    private lazy var identity = PeerIdentity(id: pairing.deviceId,
                                             name: Host.current().localizedName ?? "Mac",
                                             platform: .macos)
    private let port = RD2Mac.defaultPort
    private let services = ServiceProvider()
    private let proximity = ProximityAdvertiser()
    private var updater: Updater?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // A self-update relaunched this copy while the old one still holds the ports:
        // finish launching (the old one quits once the system reports us up), then
        // start for real once it is gone.
        if let pid = RelaunchWait.pid(from: CommandLine.arguments) {
            RelaunchWait.whenExited(pid) { [weak self] in Task { @MainActor in self?.startUp() } }
        } else {
            startUp()
        }
    }

    @MainActor private func startUp() {
        setupStatusItem()
        startServer()
        startControl()
        startDiscovery()
        startServicesProvider()
        proximity.start()   // BLE beacon for the phone's proximity gate
        enableFinderExtensionOnce()
        updater = Updater()
        updater?.start()
        // Let the first launch settle (and the system's Local Network prompt land)
        // before asking anything of the user.
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            self?.offerLaunchAtLoginOnce()
        }
    }

    /// macOS ships Finder extensions DISABLED — the "Send to Phone" item at the top
    /// of the right-click menu never appears until someone flips it on (System
    /// Settings → General → Login Items & Extensions, or `pluginkit -e use`). Doing
    /// it here is what makes the DMG a one-drag install for a non-technical user.
    /// Runs only once (UserDefaults flag), so a user who later turns it off in
    /// System Settings isn't overridden at the next launch.
    private func enableFinderExtensionOnce() {
        let key = "finderExtensionAutoEnabled"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        DispatchQueue.global(qos: .utility).async {
            let id = "net.simioni.rd2mac.FinderExtension"
            guard Self.run("/usr/bin/pluginkit", ["-e", "use", "-i", id]) else { return }
            // The Finder only loads a just-enabled extension after it relaunches
            // (it comes back by itself, so this costs the user open windows once).
            Self.run("/usr/bin/killall", ["Finder"])
        }
    }

    /// True on a machine built by build-app.sh: its LaunchAgent already starts the
    /// app at login, so the app must not offer (or toggle) a second mechanism.
    /// An installed copy from the DMG has no agent — that's where this matters.
    private var launchAgentInstalled: Bool {
        FileManager.default.fileExists(
            atPath: NSHomeDirectory() + "/Library/LaunchAgents/net.simioni.rd2mac.plist")
    }

    /// Offer launch-at-login once, on first run. The app has to be running to
    /// receive anything from the phone, so a user who never opens it manually
    /// just sees RD2Mac "not working" — this is the fix, not a nicety.
    /// SMAppService needs a real signature: the distributed (Developer ID +
    /// notarized) build registers fine; an ad-hoc dev build can't — and doesn't
    /// need to, since build-app.sh installed the LaunchAgent there.
    private func offerLaunchAtLoginOnce() {
        let key = "launchAtLoginAsked"
        guard !UserDefaults.standard.bool(forKey: key),
              !launchAgentInstalled,
              SMAppService.mainApp.status != .enabled else { return }
        UserDefaults.standard.set(true, forKey: key)
        let alert = NSAlert()
        alert.messageText = L("login.title")
        alert.informativeText = L("login.body")
        alert.addButton(withTitle: L("login.yes"))
        alert.addButton(withTitle: L("login.no"))
        NSApp.activate(ignoringOtherApps: true)
        if alert.runModal() == .alertFirstButtonReturn { setLaunchAtLogin(true) }
    }

    @objc private func toggleLaunchAtLogin() {
        setLaunchAtLogin(SMAppService.mainApp.status != .enabled)
        rebuildMenu()
    }

    /// Register/unregister with SMAppService; on failure tell the user the manual
    /// route instead of failing silently (the checkbox would just stay off).
    private func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() }
            else { try SMAppService.mainApp.unregister() }
        } catch {
            let alert = NSAlert()
            alert.alertStyle = .warning
            alert.messageText = L("login.err_title")
            alert.informativeText = String(format: L("login.err_body"), error.localizedDescription)
            alert.addButton(withTitle: L("button.ok"))
            alert.runModal()
        }
    }

    @discardableResult
    private static func run(_ path: String, _ args: [String]) -> Bool {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: path)
        task.arguments = args
        guard (try? task.run()) != nil else { return false }
        task.waitUntilExit()
        return task.terminationStatus == 0
    }

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "iphone.gen3.radiowaves.left.and.right",
                                   accessibilityDescription: "RD2Mac")
            button.image?.isTemplate = true
        }
        menu.delegate = self
        statusItem.menu = menu
        rebuildMenu()
    }

    /// Refresh right before the menu shows: the proximity row's staleness check
    /// only matters at the moment the user actually looks.
    func menuNeedsUpdate(_ menu: NSMenu) {
        rebuildMenu()
    }

    private func startServer() {
        let config = ServerConfig(
            identity: identity,
            downloadsDir: ServerConfig.defaultDownloads(),
            pairing: { [weak self] in self?.pairing.pairedPeer },
            onPair: { [weak self] msg in self?.handleIncomingPair(msg) ?? nil },
            clipboardGet: { [weak self] since in self?.clipboardGet(since: since) },
            clipboardPut: { [weak self] payload in self?.clipboardPut(payload) ?? false },
            proximityPut: { [weak self] report in
                DispatchQueue.main.async {
                    self?.phoneProximity = report
                    self?.phoneProximityAt = Date()
                    self?.rebuildMenu()
                }
            })
        server = HTTPServer(port: port, activity: .shared, tracks: isMacTransfer,
                            handler: makeMacRouter(config: config))
        do {
            try server.start()
        } catch {
            NSLog("RD2Mac: server failed to start on \(port): \(error)")
            serverError = error.localizedDescription
            rebuildMenu()   // surface it — a silent NSLog left the menu lying "connected"
        }
    }

    private func startControl() {
        let client = HTTPClient(peerName: identity.name)
        controlService = ControlService(
            peerProvider: { [weak self] in self?.activePeer },
            pairingProvider: { [weak self] in self?.pairing.pairedPeer },
            client: client,
            downloadsDir: ServerConfig.defaultDownloads()
        )
        controlServer = HTTPServer(port: RD2Mac.controlPort, loopbackOnly: true,
                                   handler: controlService.makeRouter())
        do {
            try controlServer.start()
        } catch {
            NSLog("RD2Mac: control server failed on \(RD2Mac.controlPort): \(error)")
        }
    }

    private func startDiscovery() {
        advertiser.start(name: identity.name, port: port, txt: [
            "id": identity.id,
            "name": identity.name,
            "platform": identity.platform.rawValue,
            "v": "\(identity.v)",
        ])
        browser = PeerBrowser(excludePlatform: .macos)
        browser.onPeersChanged = { [weak self] peers in
            DispatchQueue.main.async {
                self?.livePeers = peers
                self?.rebuildMenu()
            }
        }
        // Finding is only half of it: on this Wi-Fi the phone's queries go
        // unanswered, and the only packet that reaches it is our own
        // announcement — so every browse re-arm also re-announces (on its own,
        // slower period). One set of triggers drives both halves.
        browser.onRearm = { [weak self] forced in
            if self?.advertiser.republishIfDue(forced: forced) == true {
                NSLog("RD2Mac: re-announced the Bonjour service")
            }
        }
        browser.start()

        // Waking is the classic way the browse comes back dead: the app is still
        // in the menu bar, the peer list is empty, and nothing reports anything.
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.browser.restart(reason: "wake")
        }
    }

    private func startServicesProvider() {
        services.onResult = { [weak self] sent, failed in
            self?.flashStatus(failed == 0 ? "✓\(sent)" : "✓\(sent) ✗\(failed)")
        }
        NSApp.servicesProvider = services
        NSUpdateDynamicServices()
    }

    /// Show a short result badge next to the menu-bar icon, then clear it.
    /// Cancels the previous clear so overlapping flashes don't cut each other short.
    private func flashStatus(_ text: String) {
        statusItem.button?.title = " " + text
        flashReset?.cancel()
        let reset = DispatchWorkItem { [weak self] in self?.statusItem.button?.title = "" }
        flashReset = reset
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: reset)
    }

    /// A small colored SF-symbol image for a menu row (green lock, status dots).
    private func symbolImage(_ name: String, color: NSColor, description: String) -> NSImage? {
        NSImage(systemSymbolName: name, accessibilityDescription: description)?
            .withSymbolConfiguration(NSImage.SymbolConfiguration(paletteColors: [color]))
    }

    private func rebuildMenu() {
        menu.removeAllItems()
        menu.addItem(withTitle: String(format: L("menu.port"), Int(port)), action: nil, keyEquivalent: "")
        if let serverError {
            menu.addItem(withTitle: String(format: L("menu.server_failed"), serverError),
                         action: nil, keyEquivalent: "")
        }
        menu.addItem(.separator())

        let paired = pairing.pairedPeer
        let statusTitle: String
        if let paired {
            statusTitle = activePeer != nil ? String(format: L("menu.connected"), paired.name)
                                            : String(format: L("menu.searching_peer"), paired.name)
        } else {
            statusTitle = L("menu.no_phone")
        }
        let statusRow = NSMenuItem(title: statusTitle, action: nil, keyEquivalent: "")
        // Green padlock while the connected pairing carries the E2E key (it
        // always does since the ECDH handshake — absent only on a legacy pair).
        if activePeer != nil, let paired, !paired.key.isEmpty {
            statusRow.image = symbolImage("lock.fill", color: .systemGreen,
                                          description: "end-to-end encrypted")
            statusRow.toolTip = "End-to-end encrypted (AES-GCM)"
        }
        menu.addItem(statusRow)

        // The phone's proximity gate, as last reported by the phone (it is the
        // only side that measures). Shown while the option is on there, the
        // phone is on the network, and the report isn't stale.
        if activePeer != nil, let prox = phoneProximity, prox.enabled,
           Date().timeIntervalSince(phoneProximityAt) < proximityStaleAfter {
            let row = NSMenuItem(title: prox.near ? L("menu.prox_near") : L("menu.prox_far"),
                                 action: nil, keyEquivalent: "")
            row.image = symbolImage("dot.radiowaves.left.and.right",
                                    color: prox.near ? .systemGreen : .secondaryLabelColor,
                                    description: prox.near ? "phone nearby" : "phone away")
            menu.addItem(row)
        }

        // Pair / change submenu: every phone currently on the network. Match on the
        // stable id so it survives name/IP changes.
        let candidates = livePeers.sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        let pairItem = NSMenuItem(title: paired == nil ? L("menu.pair") : L("menu.change"),
                                  action: nil, keyEquivalent: "")
        let submenu = NSMenu()
        if candidates.isEmpty {
            submenu.addItem(withTitle: L("menu.searching_phones"), action: nil, keyEquivalent: "")
        } else {
            for cand in candidates {
                let item = NSMenuItem(title: cand.name, action: #selector(pairSelected(_:)),
                                      keyEquivalent: "")
                item.target = self
                item.representedObject = cand.id
                if cand.id == paired?.id { item.state = .on }
                submenu.addItem(item)
            }
        }
        pairItem.submenu = submenu
        menu.addItem(pairItem)

        if paired != nil {
            let forget = NSMenuItem(title: L("menu.forget"),
                                    action: #selector(forgetPeer), keyEquivalent: "")
            forget.target = self
            menu.addItem(forget)
        }

        menu.addItem(.separator())
        let clip = NSMenuItem(title: L("menu.clipboard"),
                              action: #selector(toggleClipboard), keyEquivalent: "")
        clip.target = self
        clip.state = pairing.receiveClipboard ? .on : .off
        menu.addItem(clip)

        let installCLI = NSMenuItem(title: L("menu.install_cli"),
                                    action: #selector(installCLITool), keyEquivalent: "")
        installCLI.target = self
        menu.addItem(installCLI)

        // Hidden on a dev machine: there the LaunchAgent owns launch-at-login, and
        // a second (always-off) checkbox would just lie about the real state.
        if !launchAgentInstalled {
            let login = NSMenuItem(title: L("menu.login"),
                                   action: #selector(toggleLaunchAtLogin), keyEquivalent: "")
            login.target = self
            login.state = SMAppService.mainApp.status == .enabled ? .on : .off
            menu.addItem(login)
        }

        menu.addItem(.separator())
        let quit = NSMenuItem(title: L("menu.quit"), action: #selector(quitApp), keyEquivalent: "q")
        quit.target = self
        menu.addItem(quit)
    }

    /// Pair with the phone the user clicked (matched by stable id). Runs the
    /// handshake: generate a shared token and POST /pair so both sides store it;
    /// only persist locally once the phone accepted (200).
    @objc private func pairSelected(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String,
              let peer = livePeers.first(where: { $0.id == id }) else { return }
        let myKeys = RD2Crypto.generateKeyPair()
        let myMsg = PairMessage(id: identity.id, name: identity.name,
                                pubKey: RD2Crypto.publicKeyBase64(myKeys))
        let client = HTTPClient(peerName: identity.name)
        Task {
            guard let reply = await client.pair(myMsg, host: peer.host, port: peer.port),
                  let session = try? RD2Crypto.agree(privateKey: myKeys,
                                                     peerPublicKeyBase64: reply.pubKey) else {
                await MainActor.run { self.flashStatus(L("flash.already_paired")) }
                return
            }
            await MainActor.run {
                self.showSAS(session.sas, peerName: peer.name) {
                    self.pairing.pair(peer, session: session)
                    self.rebuildMenu()
                }
            }
        }
    }

    /// A phone initiated pairing: derive the session, reply with our pubKey, and
    /// show the code — saving the pairing only once the user confirms it matches.
    private func handleIncomingPair(_ msg: PairMessage) -> PairMessage? {
        guard PairingAuth.acceptPair(currentId: pairing.pairedPeer?.id, incomingId: msg.id) else {
            return nil
        }
        let myKeys = RD2Crypto.generateKeyPair()
        guard let session = try? RD2Crypto.agree(privateKey: myKeys,
                                                 peerPublicKeyBase64: msg.pubKey) else { return nil }
        DispatchQueue.main.async {
            self.showSAS(session.sas, peerName: msg.name) {
                self.pairing.pair(id: msg.id, name: msg.name, session: session, port: self.port)
                self.rebuildMenu()
            }
        }
        return PairMessage(id: identity.id, name: identity.name,
                           pubKey: RD2Crypto.publicKeyBase64(myKeys))
    }

    /// Modal showing the 6-digit pairing code; runs `onConfirm` if the user confirms.
    /// A newer handshake aborts the alert already on screen: its session is stale,
    /// and confirming it would persist a key the peer no longer holds (401s).
    private func showSAS(_ sas: String, peerName: String, onConfirm: @escaping () -> Void) {
        if sasAlertVisible { NSApp.abortModal() }   // stale alert returns .abort → no save
        let alert = NSAlert()
        alert.messageText = String(format: L("sas.title"), peerName)
        alert.informativeText = String(format: L("sas.body"), sas)
        alert.addButton(withTitle: L("sas.confirm"))
        alert.addButton(withTitle: L("sas.cancel"))
        NSApp.activate(ignoringOtherApps: true)
        sasAlertVisible = true
        let response = alert.runModal()
        sasAlertVisible = false
        if response == .alertFirstButtonReturn { onConfirm() }
    }

    // MARK: - Clipboard sync

    /// Serve the Mac pasteboard to the phone when it changed since `since`
    /// (its changeCount). Image (PNG) preferred, else text. nil ⇒ unchanged (204).
    private func clipboardGet(since: Int) -> ClipboardPayload? {
        var result: ClipboardPayload?
        DispatchQueue.main.sync {
            let pb = NSPasteboard.general
            let version = pb.changeCount
            guard ClipboardEchoGuard.shouldServe(version: version, since: since,
                                                 lastInbound: lastInboundChangeCount) else { return }
            if let png = pb.data(forType: .png) {
                result = ClipboardPayload(version: version, contentType: "image/png", data: png)
            } else if let tiff = pb.data(forType: .tiff),
                      let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                result = ClipboardPayload(version: version, contentType: "image/png", data: png)
            } else if let str = pb.string(forType: .string) {
                result = ClipboardPayload(version: version, contentType: "text/plain; charset=utf-8",
                                          data: Data(str.utf8))
            }
        }
        return result
    }

    /// Put the phone's clipboard on the Mac pasteboard — only when the user
    /// turned on "receive from phone".
    private func clipboardPut(_ payload: ClipboardPayload) -> Bool {
        guard pairing.receiveClipboard else { return false }
        var ok = false
        DispatchQueue.main.sync {
            let pb = NSPasteboard.general
            pb.clearContents()
            if payload.contentType.hasPrefix("image/"), let img = NSImage(data: payload.data) {
                ok = pb.writeObjects([img])
            } else if let str = String(data: payload.data, encoding: .utf8) {
                ok = pb.setString(str, forType: .string)
            }
            if ok { lastInboundChangeCount = pb.changeCount }   // don't serve it back
        }
        return ok
    }

    @objc private func toggleClipboard() {
        pairing.receiveClipboard.toggle()
        rebuildMenu()
    }

    @objc private func forgetPeer() {
        pairing.forget()
        rebuildMenu()
    }

    // MARK: - Command-line tool install

    /// Symlink the bundled `rd2mac` CLI onto the user's PATH (`/usr/local/bin`) so a
    /// terminal — or an AI agent with shell access — can drive pulls/sends. Tries a
    /// direct symlink first, falling back to one admin-privileged shell call.
    @objc private func installCLITool() {
        // Contents/Helpers/rd2mac (not forAuxiliaryExecutable, which looks in
        // Contents/MacOS — where a case-insensitive volume would clash with RD2Mac).
        let cli = Bundle.main.bundleURL
            .appendingPathComponent("Contents/Helpers/rd2mac").path
        guard FileManager.default.fileExists(atPath: cli) else {
            return showCLIResult(ok: false, message: L("cli.err_missing"))
        }
        let dest = "/usr/local/bin/rd2mac"
        if linkDirect(cli: cli, dest: dest) || linkWithAdmin(cli: cli, dest: dest) {
            showCLIResult(ok: true, message: String(format: L("cli.ok"), dest))
        } else {
            showCLIResult(ok: false, message: L("cli.err_failed"))
        }
    }

    /// Create the symlink without elevation — works when `/usr/local/bin` is already
    /// user-writable (e.g. Homebrew present). Returns false to trigger the admin path.
    private func linkDirect(cli: String, dest: String) -> Bool {
        let fm = FileManager.default
        guard fm.isWritableFile(atPath: "/usr/local/bin") else { return false }
        try? fm.removeItem(atPath: dest)   // replace an old link/copy
        return (try? fm.createSymbolicLink(atPath: dest, withDestinationPath: cli)) != nil
    }

    /// Fall back to a single privileged shell call: the system shows one password
    /// dialog, then `mkdir -p /usr/local/bin` and (re)create the symlink.
    private func linkWithAdmin(cli: String, dest: String) -> Bool {
        let safeCli = cli.replacingOccurrences(of: "'", with: "'\\''")
        let source = "do shell script \"mkdir -p /usr/local/bin && ln -sf '\(safeCli)' '\(dest)'\" "
            + "with administrator privileges"
        var errorDict: NSDictionary?
        NSAppleScript(source: source)?.executeAndReturnError(&errorDict)
        return errorDict == nil && FileManager.default.fileExists(atPath: dest)
    }

    private func showCLIResult(ok: Bool, message: String) {
        let alert = NSAlert()
        alert.messageText = ok ? L("cli.title_ok") : L("cli.title_err")
        alert.informativeText = message
        alert.addButton(withTitle: L("button.ok"))
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    @objc private func quitApp() {
        NSApplication.shared.terminate(nil)
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory) // menu-bar only, no Dock icon
let controller = AppController()
app.delegate = controller
app.run()
