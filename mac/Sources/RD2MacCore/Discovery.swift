import Foundation

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
}

/// Publishes this device as an `_rd2mac._tcp` Bonjour service on the port the
/// HTTPServer is already listening on. Bonjour only advertises; it does not own
/// the socket, so this stays decoupled from `HTTPServer`.
///
/// Note: NetService drives its callbacks on the current thread's RunLoop, so the
/// owner (the menu-bar app) must have a running RunLoop — which AppKit provides.
public final class PeerAdvertiser: NSObject, NetServiceDelegate {
    private var service: NetService?

    public override init() { super.init() }

    public func start(name: String, port: UInt16, txt: [String: String]) {
        let svc = NetService(domain: "", type: RD2Mac.serviceType + ".",
                             name: name, port: Int32(port))
        var record: [String: Data] = [:]
        for (key, value) in txt { record[key] = Data(value.utf8) }
        svc.setTXTRecord(NetService.data(fromTXTRecord: record))
        svc.delegate = self
        svc.publish()
        service = svc
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
    private let browser = NetServiceBrowser()
    private var resolving: Set<NetService> = []
    private let excludePlatform: Platform

    /// Live counterpart peers, keyed by their (unique) Bonjour instance name.
    private var peers: [String: ResolvedPeer] = [:]

    /// Called on the RunLoop thread whenever the live set of counterpart peers
    /// changes — one resolved, or one dropped off the network. Replaces the old
    /// single-peer `onResolve` so the UI can offer a pick list.
    public var onPeersChanged: (([ResolvedPeer]) -> Void)?

    public init(excludePlatform: Platform) {
        self.excludePlatform = excludePlatform
        super.init()
    }

    public func start() {
        browser.delegate = self
        browser.searchForServices(ofType: RD2Mac.serviceType + ".", inDomain: "")
    }

    public func stop() {
        browser.stop()
        resolving.removeAll()
        peers.removeAll()
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService,
                                  moreComing: Bool) {
        service.delegate = self
        resolving.insert(service)          // retain during resolve
        service.resolve(withTimeout: 5)
    }

    public func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService,
                                  moreComing: Bool) {
        if peers.removeValue(forKey: service.name) != nil {
            onPeersChanged?(Array(peers.values))
        }
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
        peers[sender.name] = ResolvedPeer(id: id, name: sender.name, host: host,
                                          port: UInt16(sender.port), platform: platform)
        onPeersChanged?(Array(peers.values))
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
