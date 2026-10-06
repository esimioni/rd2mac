import Foundation
import Network

/// The rule that keeps a Bonjour browse alive, split out of `PeerBrowser` so it
/// can be tested without killing mDNS for real.
///
/// Why a rule at all: a `NetServiceBrowser` can stop delivering with **no error
/// and no callback** — mDNSResponder puts long-lived browses to sleep, and a
/// sleep/wake or a Wi-Fi change can kill the search outright. The object stays
/// alive, the peer list stays empty, and every answer is a truthful-but-useless
/// "phone not found" until the app is restarted (real defect, 11/08/2026).
/// So recovery cannot hang off an error callback that may never come: the browse
/// is re-armed on a schedule, and a peer that doesn't answer the re-arms must
/// answer a unicast probe or be dropped, so a dead browse can't leave a stale
/// address behind either.
struct BrowseKeepAlive {
    /// Re-arm the search this often even while it looks healthy.
    let rearmEvery: TimeInterval
    /// A peer unseen for this long (~2 missed re-arms) is stale: probed, then dropped
    /// if it doesn't answer.
    let peerTTL: TimeInterval
    /// Floor between re-arms, so a burst of wake/path events can't hammer mDNS.
    let debounce: TimeInterval
    private var lastRearm: Date

    init(now: Date = Date(), rearmEvery: TimeInterval = 60,
         peerTTL: TimeInterval = 150, debounce: TimeInterval = 5) {
        self.rearmEvery = rearmEvery
        self.peerTTL = peerTTL
        self.debounce = debounce
        self.lastRearm = now
    }

    /// `forced` = an explicit signal arrived (search failed, machine woke, network
    /// path changed): act now instead of waiting out the period.
    mutating func shouldRearm(now: Date, forced: Bool = false) -> Bool {
        guard now.timeIntervalSince(lastRearm) >= (forced ? debounce : rearmEvery) else {
            return false
        }
        lastRearm = now
        return true
    }

    func isStale(seen: Date, now: Date) -> Bool { now.timeIntervalSince(seen) > peerTTL }
}

/// A peer resolved on the local network to a concrete address.
public struct ResolvedPeer: Equatable, Sendable {
    public let id: String     // peer's stable per-install id (from the TXT record)
    public let name: String
    public let host: String   // numeric IP literal (IPv4 preferred)
    public let port: UInt16
    public let platform: Platform

    public init(id: String = "", name: String, host: String, port: UInt16, platform: Platform) {
        self.id = id
        self.name = name
        self.host = host
        self.port = port
        self.platform = platform
    }

    /// Whether a `/ping` reply came from this very install. The address may have
    /// gone to another device since the browse last saw it, so only the stable id
    /// counts — and a peer without one (a pre-id phone) can't be confirmed this way.
    func isAnswered(by identity: PeerIdentity?) -> Bool {
        guard let identity, !id.isEmpty else { return false }
        return identity.id == id
    }
}

/// Publishes this device as an `_rd2mac._tcp` Bonjour service on the port the
/// HTTPServer is already listening on. Bonjour only advertises; it does not own
/// the socket, so this stays decoupled from `HTTPServer`.
///
/// Note: NetService drives its callbacks on the current thread's RunLoop, so the
/// owner (the menu-bar app) must have a running RunLoop — which AppKit provides.
public final class PeerAdvertiser: NSObject, NetServiceDelegate {
    private var service: NetService?
    private var published: (name: String, port: UInt16, txt: [String: String])?
    /// Same rule as the browse keep-alive, longer period: re-announcing is what
    /// reaches a phone whose queries we never answer (measured 11/08/2026 — the
    /// phone found this Mac the instant it re-published, and not once in 70 s of
    /// active browsing before that; this Wi-Fi drops the query or the reply, and
    /// a gratuitous announcement is the packet that does get through).
    private var keepAlive = BrowseKeepAlive(rearmEvery: 300)

    public override init() { super.init() }

    public func start(name: String, port: UInt16, txt: [String: String]) {
        published = (name, port, txt)
        let svc = NetService(domain: "", type: RD2Mac.serviceType + ".",
                             name: name, port: Int32(port))
        var record: [String: Data] = [:]
        for (key, value) in txt { record[key] = Data(value.utf8) }
        svc.setTXTRecord(NetService.data(fromTXTRecord: record))
        svc.delegate = self
        svc.publish()
        service = svc
    }

    /// Re-announce if the period elapsed (or `forced`, on wake / network change).
    /// A device that already has us resolved isn't listening — the Android side
    /// sleeps its browse once it resolves — so this only ever reaches someone who
    /// is currently searching. Returns whether it re-announced.
    @discardableResult
    public func republishIfDue(now: Date = Date(), forced: Bool = false) -> Bool {
        guard let published, keepAlive.shouldRearm(now: now, forced: forced) else { return false }
        stop()
        start(name: published.name, port: published.port, txt: published.txt)
        return true
    }

    public func stop() {
        service?.stop()
        service = nil
    }

    public func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        NSLog("RD2Mac: Bonjour publish failed: \(errorDict)")
    }
}

/// Browses for the counterpart peer and resolves it to `ResolvedPeer`.
/// Ignores our own platform's services (so a Mac only surfaces the phone).
public final class PeerBrowser: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    /// Asks a peer the browse stopped hearing from whether it is still there, and
    /// calls back ON THE MAIN THREAD with whether it answered as itself.
    public typealias Probe = (_ peer: ResolvedPeer, _ answered: @escaping (Bool) -> Void) -> Void

    private var browser = NetServiceBrowser()
    private var resolving: Set<NetService> = []
    private let excludePlatform: Platform
    private let probe: Probe
    private let now: () -> Date

    /// A resolved peer plus when it last answered — the timestamp is what lets a
    /// peer that stopped answering be dropped (see `BrowseKeepAlive`).
    struct Entry: Equatable {
        var peer: ResolvedPeer
        var seen: Date
    }

    /// Live counterpart peers, keyed by their (unique) Bonjour instance name.
    private var entries: [String: Entry] = [:]
    /// Peers with a probe out, so a slow answer isn't asked again on the next tick.
    private var probing: Set<String> = []
    private var keepAlive: BrowseKeepAlive
    private var timer: Timer?
    private let pathMonitor = NWPathMonitor()
    /// Last list handed to `onPeersChanged`, so a re-arm's repeated resolves
    /// don't rebuild the menu every minute for an unchanged peer set.
    private var published: [ResolvedPeer] = []

    /// Called on the RunLoop thread whenever the live set of counterpart peers
    /// changes — one resolved, or one dropped off the network. Replaces the old
    /// single-peer `onResolve` so the UI can offer a pick list.
    public var onPeersChanged: (([ResolvedPeer]) -> Void)?

    /// Fired on every re-arm, with `forced` set when an explicit signal drove it
    /// (search failed, wake, network path change). The app hangs the advertiser's
    /// re-announcement here so both halves of discovery share one set of triggers
    /// — one timer, one path monitor, one wake observer.
    public var onRearm: ((_ forced: Bool) -> Void)?

    /// The real probe: `GET /ping` at the peer's last address. Unicast reaches a
    /// phone whose Wi-Fi sleeps through multicast — measured 06/10/2026: the S23
    /// left five re-arms in a row unanswered (5 min) while it answered ping.
    /// Retried like every call to the phone (the Local Network blip after an update).
    public static func pingProbe(peerName: String) -> Probe {
        { peer, answered in
            Task {
                let client = HTTPClient(peerName: peerName, timeout: 5)
                let identity = try? await ControlService.retrying {
                    guard let identity = await client.ping(host: peer.host, port: peer.port) else {
                        throw RD2MacError.peerNotFound
                    }
                    return identity
                }
                let isPeer = peer.isAnswered(by: identity)
                DispatchQueue.main.async { answered(isPeer) }
            }
        }
    }

    public init(excludePlatform: Platform, probe: @escaping Probe,
                now: @escaping () -> Date = Date.init) {
        self.excludePlatform = excludePlatform
        self.probe = probe
        self.now = now
        self.keepAlive = BrowseKeepAlive(now: now())
        super.init()
    }

    public func start() {
        arm()
        // Drives both halves of the keep-alive: re-arm the search, and expire
        // peers that stopped answering it. Coarse tolerance — this must never be
        // a reason for the Mac to wake up.
        let timer = Timer(timeInterval: 15, repeats: true) { [weak self] _ in self?.tick() }
        timer.tolerance = 5
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer

        // Wi-Fi change / VPN up / interface flap: the browse is dead or now
        // pointing at the wrong link, and nothing else would tell us.
        pathMonitor.pathUpdateHandler = { [weak self] _ in
            DispatchQueue.main.async { self?.restart(reason: "network path changed") }
        }
        pathMonitor.start(queue: DispatchQueue(label: "net.simioni.rd2mac.path"))
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
        pathMonitor.cancel()
        browser.stop()
        resolving.removeAll()
        entries.removeAll()
        probing.removeAll()
        published = []
    }

    /// Re-arm now because something said so (search failed, machine woke, network
    /// path changed). Debounced, so a burst of events costs one restart.
    public func restart(reason: String) {
        guard keepAlive.shouldRearm(now: Date(), forced: true) else { return }
        NSLog("RD2Mac: re-arming Bonjour browse (\(reason))")
        arm()
        onRearm?(true)
    }

    /// Start (or restart) the search on a fresh browser. A fresh object rather
    /// than `stop()` + search on the old one: a browser that died silently is
    /// exactly the thing we can't trust to restart cleanly. The old delegate is
    /// cleared first so its late callbacks can't remove live peers.
    private func arm() {
        browser.delegate = nil
        browser.stop()
        browser = NetServiceBrowser()
        browser.delegate = self
        browser.searchForServices(ofType: RD2Mac.serviceType + ".", inDomain: "")
    }

    private func tick() {
        sweep()
        if keepAlive.shouldRearm(now: now()) {
            // Only worth a log line while nothing is found — that's the state the
            // re-arm exists for, and it's the breadcrumb if it ever fails again.
            if entries.isEmpty { NSLog("RD2Mac: no peer yet — re-arming Bonjour browse") }
            arm()
            onRearm?(false)
        }
    }

    /// The expiry half of the keep-alive, run on every tick. A peer the browse
    /// stopped confirming is not dropped on that alone: multicast silence is not
    /// absence (a dozing phone sleeps through queries it would answer by unicast),
    /// so it is asked directly, and only a peer that fails that too is gone.
    func sweep() {
        let at = now()
        for (name, entry) in entries
        where keepAlive.isStale(seen: entry.seen, now: at) && !probing.contains(name) {
            probing.insert(name)
            probe(entry.peer) { [weak self] isPeer in self?.settleProbe(of: entry.peer, isPeer: isPeer) }
        }
    }

    /// A probe came back. The browse may have re-confirmed the peer meanwhile, or
    /// found it at another address — either way its word is newer than this answer.
    private func settleProbe(of peer: ResolvedPeer, isPeer: Bool) {
        probing.remove(peer.name)
        let at = now()
        guard let entry = entries[peer.name], entry.peer == peer,
              keepAlive.isStale(seen: entry.seen, now: at) else { return }
        if isPeer {
            entries[peer.name]?.seen = at
        } else {
            entries.removeValue(forKey: peer.name)
            publish()
        }
    }

    /// The browse resolved a peer: it is there, at this address, now.
    func record(_ peer: ResolvedPeer) {
        entries[peer.name] = Entry(peer: peer, seen: now())
        publish()
    }

    private func publish() {
        let peers = entries.values.map(\.peer).sorted { $0.name < $1.name }
        guard peers != published else { return }
        published = peers
        onPeersChanged?(peers)
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService,
                                  moreComing: Bool) {
        service.delegate = self
        resolving.insert(service)          // retain during resolve
        service.resolve(withTimeout: 5)
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService,
                                  moreComing: Bool) {
        if entries.removeValue(forKey: service.name) != nil {
            publish()
        }
    }

    /// The one failure Bonjour does report. It is not the only way a browse dies
    /// — hence the timer — but when it fires, recover at once.
    public func netServiceBrowser(_ browser: NetServiceBrowser,
                                  didNotSearch errorDict: [String: NSNumber]) {
        NSLog("RD2Mac: Bonjour browse failed: \(errorDict)")
        restart(reason: "search failed")
    }

    public func netServiceDidResolveAddress(_ sender: NetService) {
        defer { resolving.remove(sender) }
        guard let addresses = sender.addresses, !addresses.isEmpty else { return }

        var platform: Platform = .unknown
        var id = ""
        if let txtData = sender.txtRecordData() {
            let txt = NetService.dictionary(fromTXTRecord: txtData)
            if let raw = txt["platform"], let str = String(data: raw, encoding: .utf8) {
                platform = Platform(rawValue: str) ?? .unknown
            }
            if let raw = txt["id"], let str = String(data: raw, encoding: .utf8) {
                id = str
            }
        }
        if platform == excludePlatform { return }   // ignore ourselves

        guard let host = Self.preferredIP(addresses) else { return }
        record(ResolvedPeer(id: id, name: sender.name, host: host,
                            port: UInt16(sender.port), platform: platform))
    }

    public func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving.remove(sender)
    }

    // MARK: - sockaddr → IP string (IPv4 preferred)

    private static func preferredIP(_ addresses: [Data]) -> String? {
        var ipv6: String?
        for addr in addresses {
            guard let (host, isV6) = ipString(addr) else { continue }
            if !isV6 { return host }         // IPv4 wins
            if ipv6 == nil { ipv6 = host }
        }
        return ipv6
    }

    private static func ipString(_ addr: Data) -> (host: String, ipv6: Bool)? {
        addr.withUnsafeBytes { raw -> (String, Bool)? in
            guard let base = raw.baseAddress else { return nil }
            let sa = base.assumingMemoryBound(to: sockaddr.self)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let rc = getnameinfo(sa, socklen_t(addr.count), &host, socklen_t(host.count),
                                 nil, 0, NI_NUMERICHOST)
            guard rc == 0 else { return nil }
            let isV6 = sa.pointee.sa_family == sa_family_t(AF_INET6)
            return (String(cString: host), isV6)
        }
    }
}
