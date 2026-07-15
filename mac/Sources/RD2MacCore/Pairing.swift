import Foundation

/// The peer a user has explicitly chosen to talk to, persisted so it never
/// changes until they pick another (or forget). Pairing is independent per side:
/// each app remembers its own counterpart. `host`/`port` are a last-known cache
/// (for display before discovery re-resolves, and a manual fallback).
public struct PairedPeer: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var host: String
    public var port: UInt16
    /// Auth token (header), derived from the ECDH shared secret at pairing. The
    /// server rejects a mismatch (or any request when unpaired) with 401.
    public var token: String
    /// AES-256 key (base64), derived from the same shared secret. Encrypts the
    /// content of every transfer so nothing is readable on the wire.
    public var key: String

    public init(id: String, name: String, host: String, port: UInt16,
                token: String, key: String = "") {
        self.id = id
        self.name = name
        self.host = host
        self.port = port
        self.token = token
        self.key = key
    }
}

/// Pure authorization rules for the handshake — no I/O, so they're unit-tested
/// directly and mirrored byte-for-byte on the Android side.
public enum PairingAuth {
    /// A transfer is allowed only when we're paired and the caller presents the
    /// exact shared token. No pairing → nothing is accepted.
    public static func authorize(pairedToken: String?, requestToken: String?) -> Bool {
        guard let pairedToken, !pairedToken.isEmpty else { return false }
        return requestToken == pairedToken
    }

    /// Accept a `POST /pair` when we're not paired yet, or when it's the same peer
    /// re-pairing. Reject a different peer trying to hijack an established pairing
    /// (the owner must "Forget" first).
    public static func acceptPair(currentId: String?, incomingId: String) -> Bool {
        guard let currentId, !currentId.isEmpty else { return true }
        return currentId == incomingId
    }
}

/// Persists this device's stable id and the chosen peer in `UserDefaults`.
/// The Android side mirrors this with `SharedPreferences`.
public final class PairingStore {
    private let defaults: UserDefaults

    private enum Key {
        static let deviceId = "rd2mac.deviceId"
        static let paired = "rd2mac.pairedPeer"
        static let receiveClipboard = "rd2mac.receiveClipboard"
    }

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Whether this Mac accepts the phone's clipboard (the receive switch). Off by default.
    public var receiveClipboard: Bool {
        get { defaults.bool(forKey: Key.receiveClipboard) }
        set { defaults.set(newValue, forKey: Key.receiveClipboard) }
    }

    /// Stable per-install id, generated once on first read and persisted.
    public var deviceId: String {
        if let existing = defaults.string(forKey: Key.deviceId), !existing.isEmpty {
            return existing
        }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: Key.deviceId)
        return fresh
    }

    public var pairedPeer: PairedPeer? {
        get {
            guard let data = defaults.data(forKey: Key.paired) else { return nil }
            return try? JSONDecoder().decode(PairedPeer.self, from: data)
        }
        set {
            if let newValue, let data = try? JSONEncoder().encode(newValue) {
                defaults.set(data, forKey: Key.paired)
            } else {
                defaults.removeObject(forKey: Key.paired)
            }
        }
    }

    /// Remember `peer` as the fixed counterpart, with the derived crypto session.
    public func pair(_ peer: ResolvedPeer, session: RD2Crypto.Session) {
        pair(id: peer.id, name: peer.name, session: session, host: peer.host, port: peer.port)
    }

    /// Same, for a peer known only by id/name (incoming pairing before discovery
    /// resolves it — host/port are just a last-known cache anyway).
    public func pair(id: String, name: String, session: RD2Crypto.Session,
                     host: String = "", port: UInt16 = RD2Mac.defaultPort) {
        pairedPeer = PairedPeer(id: id, name: name, host: host, port: port,
                                token: session.token, key: session.keyBytes.base64EncodedString())
    }

    /// Drop the pairing so the user can pick again.
    public func forget() {
        pairedPeer = nil
    }
}

/// Which discovered peer we actually talk to. Pure so it's trivially testable and
/// shared by both the CLI/control path and the menu UI.
public enum PeerSelection {
    /// The live peer whose id matches the paired one; `nil` when unpaired or the
    /// paired peer isn't on the network right now.
    public static func activePeer(from live: [ResolvedPeer], pairedId: String?) -> ResolvedPeer? {
        guard let pairedId, !pairedId.isEmpty else { return nil }
        return live.first { $0.id == pairedId }
    }
}
