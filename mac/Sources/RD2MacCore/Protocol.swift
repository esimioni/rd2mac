import Foundation
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

/// Wire-protocol constants and shared types. Mirror of `PROTOCOL.md`.
public enum RD2Mac {
    /// Bonjour service type advertised/browsed by both peers.
    public static let serviceType = "_rd2mac._tcp"
    /// Protocol version. Bump on any breaking change (see PROTOCOL.md "Versioning").
    /// v2: sealed /receive response + sealed filename headers on paired transfers.
    public static let protocolVersion = 2
    /// Preferred TCP port. Bonjour still advertises the *actual* bound port; this
    /// is the assumed port when the user configures a manual IP fallback.
    public static let defaultPort: UInt16 = 53318
    /// Loopback-only control port the `rd2mac` CLI talks to (never on the LAN).
    public static let controlPort: UInt16 = 53319
    /// BLE beacon the Mac advertises so the phone can gate clipboard sync on
    /// proximity (RSSI). Mirror of the Kotlin constant.
    public static let bleServiceUUID = "cd8a379d-ddd9-4f84-8a70-98aa480baee3"

    public enum Header {
        public static let token = "X-RD2Mac-Token"
        public static let filename = "X-RD2Mac-Filename"
        public static let peer = "X-RD2Mac-Peer"
        public static let clipVersion = "X-RD2Mac-Clip-Version"
    }

    public enum Path {
        public static let ping = "/ping"
        public static let pair = "/pair"
        public static let receive = "/receive"
        public static let clipboard = "/clipboard"
        public static let proximity = "/proximity"
        public static let screenshotLatest = "/screenshot/latest"
        public static let mediaLatest = "/media/latest"
    }
}

/// Body of `POST /proximity` — the phone's proximity-gate status, mirrored in
/// the Mac's menu. Only the phone measures (both sync directions are
/// phone-driven); the Mac just displays the last report and expires it when the
/// keepalives stop.
public struct ProximityReport: Codable, Equatable, Sendable {
    public var enabled: Bool
    public var near: Bool

    public init(enabled: Bool, near: Bool) {
        self.enabled = enabled
        self.near = near
    }
}

/// A clipboard payload exchanged over `/clipboard`. `version` is the Mac's
/// pasteboard changeCount, used so the phone only re-copies when it actually
/// changed (poll returns `204` otherwise).
public struct ClipboardPayload: Sendable {
    public var version: Int
    public var contentType: String   // "text/plain; charset=utf-8" or "image/png"
    public var data: Data

    public init(version: Int, contentType: String, data: Data) {
        self.version = version
        self.contentType = contentType
        self.data = data
    }
}

/// Cuts the clipboard sync loop on the Mac side: after the phone POSTs its
/// clipboard here, our pasteboard changeCount bumps — serving that same version
/// back to the phone's poll would bounce its own content forever (the phone has
/// the mirror guard by content fingerprint). Pure, so it's unit-tested.
public enum ClipboardEchoGuard {
    /// - Parameters:
    ///   - version: current pasteboard changeCount
    ///   - since: the version the phone already has (its `?since=`)
    ///   - lastInbound: changeCount produced by the phone's own POST, if any
    public static func shouldServe(version: Int, since: Int, lastInbound: Int?) -> Bool {
        version != since && version != lastInbound
    }
}

/// Body of `POST /pair` — the initiator announces itself plus its ephemeral ECDH
/// public key. The responder replies with the same shape (its id/name/pubKey);
/// both derive the shared secret locally. No secret ever travels the wire.
public struct PairMessage: Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var pubKey: String   // X9.63 uncompressed, base64

    public init(id: String, name: String, pubKey: String) {
        self.id = id
        self.name = name
        self.pubKey = pubKey
    }
}

public enum Platform: String, Codable, Sendable {
    case macos, android, unknown
}

/// Body of `GET /ping` — the peer's identity.
public struct PeerIdentity: Codable, Equatable, Sendable {
    /// Stable per-install id (UUID). Lets a peer be recognized across name/IP
    /// changes — it's the key the pairing selection matches on.
    public var id: String
    public var name: String
    public var platform: Platform
    public var v: Int

    public init(id: String = "", name: String, platform: Platform, v: Int = RD2Mac.protocolVersion) {
        self.id = id
        self.name = name
        self.platform = platform
        self.v = v
    }

    /// Tolerant decode: a peer from before `id` existed simply reports "".
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        name = try c.decode(String.self, forKey: .name)
        platform = try c.decodeIfPresent(Platform.self, forKey: .platform) ?? .unknown
        v = try c.decodeIfPresent(Int.self, forKey: .v) ?? RD2Mac.protocolVersion
    }
}

/// Success body of `POST /receive`.
public struct ReceiveResult: Codable, Equatable, Sendable {
    public var saved: String
    public var bytes: Int

    public init(saved: String, bytes: Int) {
        self.saved = saved
        self.bytes = bytes
    }
}

// MARK: - Filename & MIME helpers (shared by server save + client send)

public enum FileNaming {
    /// Strip path separators and control chars so a peer-supplied filename can't
    /// escape the destination directory. Falls back to a timestamped name.
    public static func sanitize(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lastComponent = (trimmed as NSString).lastPathComponent
        let cleaned = lastComponent
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: "\u{0}", with: "")
        if cleaned.isEmpty || cleaned == "." || cleaned == ".." {
            let stamp = ISO8601DateFormatter().string(from: Date())
                .replacingOccurrences(of: ":", with: "-")
            return "rd2mac-\(stamp)"
        }
        return cleaned
    }

    /// Return a non-colliding URL in `dir` for `filename`, appending ` (2)`,
    /// ` (3)`, … before the extension if needed. Mirrors Finder/Downloads behavior.
    public static func uniqueDestination(for filename: String, in dir: URL) -> URL {
        let safe = sanitize(filename)
        var candidate = dir.appendingPathComponent(safe)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }

        let ext = (safe as NSString).pathExtension
        let base = (safe as NSString).deletingPathExtension
        var n = 2
        repeat {
            let name = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            candidate = dir.appendingPathComponent(name)
            n += 1
        } while FileManager.default.fileExists(atPath: candidate.path)
        return candidate
    }

    /// Best-effort MIME type from a filename extension.
    public static func mimeType(for filename: String) -> String {
        let ext = (filename as NSString).pathExtension
        #if canImport(UniformTypeIdentifiers)
        if let type = UTType(filenameExtension: ext), let mime = type.preferredMIMEType {
            return mime
        }
        #endif
        return "application/octet-stream"
    }
}

// MARK: - Header encoding for filenames

/// Percent-encode everything except ASCII alphanumerics. Deliberately narrow so
/// it is byte-for-byte identical to the Kotlin `HeaderCodec` on the Android side
/// (which allows only [A-Za-z0-9]); anything else becomes %XX of its UTF-8 bytes.
private let rd2macHeaderAllowed: CharacterSet = {
    var set = CharacterSet()
    set.insert(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
    return set
}()

public extension String {
    /// Percent-encode for safe transport in an HTTP header value.
    var rd2macHeaderEncoded: String {
        addingPercentEncoding(withAllowedCharacters: rd2macHeaderAllowed) ?? self
    }

    /// Decode a value produced by `rd2macHeaderEncoded`.
    var rd2macHeaderDecoded: String {
        removingPercentEncoding ?? self
    }
}

/// Filename headers on paired transfers travel encrypted — base64(seal(utf8))
/// with the pairing key — so a sniffer who captured the auth token still learns
/// nothing from `X-RD2Mac-Filename`. Without a key (pre-pairing, loopback
/// control) falls back to plain percent-encoding. Mirror of the Kotlin `HeaderCrypt`.
public enum HeaderCrypt {
    public static func encode(_ value: String, key: Data?) -> String {
        guard let key, let sealed = try? RD2Crypto.seal(Data(value.utf8), keyBytes: key) else {
            return value.rd2macHeaderEncoded
        }
        return sealed.base64EncodedString()
    }

    /// Sealed value → plaintext; anything that doesn't open (plain-encoded peer,
    /// garbage) falls back to the percent decode — a filename never hard-fails.
    public static func decode(_ value: String, key: Data?) -> String {
        if let key, let blob = Data(base64Encoded: value),
           let opened = try? RD2Crypto.open(blob, keyBytes: key),
           let plain = String(data: opened, encoding: .utf8) {
            return plain
        }
        return value.rd2macHeaderDecoded
    }
}
