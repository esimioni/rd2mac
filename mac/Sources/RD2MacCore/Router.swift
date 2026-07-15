import Foundation

/// Configuration for the Mac-side HTTP server.
public struct ServerConfig {
    public var identity: PeerIdentity
    /// Where received files land. Everything → this directory (default ~/Downloads).
    public var downloadsDir: URL
    /// Current pairing; its token gates transfers. nil ⇒ not paired ⇒ reject all.
    public var pairing: () -> PairedPeer?
    /// Handle `POST /pair` (peer's ECDH message); return our reply message to
    /// accept, or nil to reject (403). Deriving the session + showing the SAS is
    /// the app's job inside this closure.
    public var onPair: (PairMessage) -> PairMessage?
    /// Serve `GET /clipboard?since=`; nil ⇒ unchanged (204).
    public var clipboardGet: (Int) -> ClipboardPayload?
    /// Handle `POST /clipboard`; false ⇒ receiving is off (403).
    public var clipboardPut: (ClipboardPayload) -> Bool
    /// Handle `POST /proximity` — the phone's proximity-gate status (menu display).
    public var proximityPut: (ProximityReport) -> Void

    public init(identity: PeerIdentity, downloadsDir: URL,
                pairing: @escaping () -> PairedPeer? = { nil },
                onPair: @escaping (PairMessage) -> PairMessage? = { _ in nil },
                clipboardGet: @escaping (Int) -> ClipboardPayload? = { _ in nil },
                clipboardPut: @escaping (ClipboardPayload) -> Bool = { _ in false },
                proximityPut: @escaping (ProximityReport) -> Void = { _ in }) {
        self.identity = identity
        self.downloadsDir = downloadsDir
        self.pairing = pairing
        self.onPair = onPair
        self.clipboardGet = clipboardGet
        self.clipboardPut = clipboardPut
        self.proximityPut = proximityPut
    }

    public static func defaultDownloads() -> URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("Downloads")
    }
}

/// Builds the request handler for the Mac server: `/ping` and `/receive`.
/// `/screenshot` and `/media` are 404 here — the Mac is never the source of the
/// phone's gallery; the Android app serves those.
public func makeMacRouter(config: ServerConfig) -> (HTTPRequest) -> HTTPResponse {
    return { request in
        // Transfers + clipboard require the paired token; /ping and /pair don't.
        let authorized = PairingAuth.authorize(pairedToken: config.pairing()?.token,
                                               requestToken: request.header(RD2Mac.Header.token))
        switch (request.method, request.path) {
        case ("GET", RD2Mac.Path.ping):
            return .json(200, config.identity)   // open: health + discovery

        case ("POST", RD2Mac.Path.pair):
            guard let body = try? JSONDecoder().decode(PairMessage.self, from: request.body) else {
                return .text(400, "bad pair request")
            }
            guard let reply = config.onPair(body) else {
                return .text(403, "already paired with another device")
            }
            return .json(200, reply)

        case ("POST", RD2Mac.Path.receive):
            guard authorized else { return .text(401, "unauthorized") }
            let paired = config.pairing()
            guard let plain = openBody(request.body, paired) else {
                return .text(400, "decrypt failed")
            }
            let name = request.header(RD2Mac.Header.filename)
                .map { HeaderCrypt.decode($0, key: pairedKey(paired)) } ?? "rd2mac-file"
            let saved = handleReceive(filename: name, body: plain, into: config.downloadsDir)
            guard saved.status == 200 else { return saved }
            // Sealed response: proves to the sender WE hold the pairing key — a
            // bare 200 could be forged by an active MITM that never persisted
            // anything (the phantom-send bug, adversarial edition).
            guard let enc = sealBody(saved.body, paired) else {
                return .text(500, "encrypt failed")
            }
            return HTTPResponse(status: 200,
                                headers: ["Content-Type": "application/octet-stream"],
                                body: enc)

        case ("GET", RD2Mac.Path.clipboard):
            guard authorized else { return .text(401, "unauthorized") }
            let since = Int(request.query["since"] ?? "") ?? -1
            guard let payload = config.clipboardGet(since) else {
                return HTTPResponse(status: 204, headers: [:], body: Data())
            }
            guard let enc = sealBody(payload.data, config.pairing()) else {
                return .text(500, "encrypt failed")
            }
            return HTTPResponse(status: 200,
                                headers: ["Content-Type": payload.contentType,
                                          RD2Mac.Header.clipVersion: "\(payload.version)"],
                                body: enc)

        case ("POST", RD2Mac.Path.clipboard):
            guard authorized else { return .text(401, "unauthorized") }
            guard let plain = openBody(request.body, config.pairing()) else {
                return .text(400, "decrypt failed")
            }
            let payload = ClipboardPayload(version: 0,
                                           contentType: request.header("Content-Type") ?? "text/plain",
                                           data: plain)
            return config.clipboardPut(payload) ? .text(200, "ok")
                                                : .text(403, "clipboard receiving is off")

        case ("POST", RD2Mac.Path.proximity):
            guard authorized else { return .text(401, "unauthorized") }
            guard let plain = openBody(request.body, config.pairing()),
                  let report = try? JSONDecoder().decode(ProximityReport.self, from: plain) else {
                return .text(400, "bad proximity report")
            }
            config.proximityPut(report)
            return .text(200, "ok")

        default:
            return .text(404, "no route for \(request.method) \(request.path)")
        }
    }
}

/// Persist bytes into `dir` with a collision-safe name, returning where they
/// landed. Shared by the `/receive` handler and the CLI's pull-screenshot save.
public func persist(_ data: Data, preferredName: String, into dir: URL) throws -> ReceiveResult {
    let destination = FileNaming.uniqueDestination(for: preferredName, in: dir)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    try data.write(to: destination, options: .atomic)
    return ReceiveResult(saved: destination.path, bytes: data.count)
}

/// Persist the uploaded bytes to disk, then respond. The write completes BEFORE
/// the 200 is returned — a successful response means the file really landed
/// (the core guarantee against phantom sends).
func handleReceive(filename: String, body: Data, into dir: URL) -> HTTPResponse {
    do {
        let result = try persist(body, preferredName: filename, into: dir)
        return .json(200, result)
    } catch {
        return .text(500, "write failed: \(error.localizedDescription)")
    }
}

/// The paired peer's AES key bytes, or nil when absent/invalid.
func pairedKey(_ paired: PairedPeer?) -> Data? {
    guard let s = paired?.key, let k = Data(base64Encoded: s), k.count == 32 else { return nil }
    return k
}

/// Open/seal a transfer body with the paired peer's AES key (nil ⇒ no valid key).
func openBody(_ data: Data, _ paired: PairedPeer?) -> Data? {
    guard let k = pairedKey(paired) else { return nil }
    return try? RD2Crypto.open(data, keyBytes: k)
}
func sealBody(_ data: Data, _ paired: PairedPeer?) -> Data? {
    guard let k = pairedKey(paired) else { return nil }
    return try? RD2Crypto.seal(data, keyBytes: k)
}
