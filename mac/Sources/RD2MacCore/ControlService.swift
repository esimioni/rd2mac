import Foundation

/// Loopback-only control API that the `rd2mac` CLI drives. It reuses the app's
/// live discovered peer and its HTTP client, so the CLI never has to run Bonjour
/// itself. Routes: `GET /peer`, `POST /pull-screenshot`, `POST /pull-media`.
public final class ControlService {
    private let peerProvider: () -> ResolvedPeer?
    private let pairingProvider: () -> PairedPeer?
    private let client: HTTPClient
    private let downloadsDir: URL

    public init(peerProvider: @escaping () -> ResolvedPeer?,
                pairingProvider: @escaping () -> PairedPeer? = { nil },
                client: HTTPClient,
                downloadsDir: URL) {
        self.peerProvider = peerProvider
        self.pairingProvider = pairingProvider
        self.client = client
        self.downloadsDir = downloadsDir
    }

    /// The HTTP client carrying the paired token + AES key (auth + body encryption).
    private var authedClient: HTTPClient {
        var c = client
        if let p = pairingProvider() {
            c.token = p.token
            c.keyBytes = Data(base64Encoded: p.key)
        }
        return c
    }

    struct PeerInfo: Codable { let name: String; let host: String; let port: Int }

    /// Request target for `POST /send` with a local file path as the `?path=`
    /// value. Encodes everything outside RFC 3986 unreserved so `&`, `=`, `+`,
    /// spaces and UTF-8 names survive the server's query parser. Clients of the
    /// control endpoint (Services menu, scripts) should build the URL with this.
    public static func sendTarget(forPath path: String) -> String {
        let unreserved = CharacterSet(
            charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let encoded = path.addingPercentEncoding(withAllowedCharacters: unreserved) ?? path
        return "/send?path=\(encoded)"
    }

    public func makeRouter() -> (HTTPRequest) -> HTTPResponse {
        return { [self] request in
            switch (request.method, request.path) {
            case ("GET", "/peer"):
                return peerInfo()
            case ("POST", "/pull-screenshot"):
                let count = PullArgs.count(request.query["count"].map { [$0] } ?? [])
                return pull(basePath: RD2Mac.Path.screenshotLatest, count: count)
            case ("POST", "/pull-media"):
                let type = request.query["type"] ?? "image"
                let count = PullArgs.count(request.query["count"].map { [$0] } ?? [])
                return pull(basePath: RD2Mac.Path.mediaLatest + "?type=\(type)", count: count)
            case ("POST", "/send"):
                guard let path = request.query["path"], !path.isEmpty else {
                    return .text(400, "missing ?path=")
                }
                return send(path: path)
            case ("POST", "/send-file"):
                return sendFileBytes(request)
            default:
                return .text(404, "no control route for \(request.method) \(request.path)")
            }
        }
    }

    private func peerInfo() -> HTTPResponse {
        guard let peer = peerProvider() else {
            return .text(404, "phone not found on the network")
        }
        return .json(200, PeerInfo(name: peer.name, host: peer.host, port: Int(peer.port)))
    }

    /// Download the `count` most-recent items (the phone serves the k-th newest for
    /// `?skip=k`) and persist each to Downloads. Runs the async transfers and blocks
    /// this control thread until they finish (loopback, low volume). Returns a JSON
    /// array of ReceiveResult — one per saved file — so the CLI prints one path per
    /// line. Stops early, keeping what it already pulled, when the phone runs out of
    /// items (404); errors only when nothing at all was pulled.
    private func pull(basePath: String, count: Int) -> HTTPResponse {
        guard let peer = peerProvider() else {
            return .text(404, "phone not found on the network")
        }
        let semaphore = DispatchSemaphore(value: 0)
        var response = HTTPResponse.text(500, "unknown error")
        Task {
            defer { semaphore.signal() }
            var results: [ReceiveResult] = []
            var lastError: String?
            for skip in 0..<max(1, count) {
                let separator = basePath.contains("?") ? "&" : "?"
                let path = basePath + "\(separator)skip=\(skip)"
                do {
                    let file = try await authedClient.download(path: path, host: peer.host, port: peer.port)
                    results.append(try persist(file.data, preferredName: file.filename, into: downloadsDir))
                } catch {
                    lastError = error.localizedDescription
                    break   // phone out of items (404) or a transfer failure — keep what we have
                }
            }
            if results.isEmpty {
                response = .text(502, "pull failed: \(lastError ?? "no items")")
            } else {
                response = .json(200, results)
            }
        }
        semaphore.wait()
        return response
    }

    /// `POST /send?path=` — read a local file and send it (CLI path).
    private func send(path: String) -> HTTPResponse {
        guard FileManager.default.fileExists(atPath: path) else {
            return .text(400, "no such file: \(path)")
        }
        guard let data = try? Data(contentsOf: URL(fileURLWithPath: path)) else {
            return .text(400, "cannot read: \(path)")
        }
        return sendData(data, filename: (path as NSString).lastPathComponent)
    }

    /// `POST /send-file` — body is the file bytes, filename in the header. Used by
    /// the Share Extension (it reads the shared file's bytes and posts them here,
    /// so nothing depends on a cross-process file path).
    private func sendFileBytes(_ request: HTTPRequest) -> HTTPResponse {
        let name = request.header(RD2Mac.Header.filename)?.rd2macHeaderDecoded ?? "rd2mac-file"
        return sendData(request.body, filename: name)
    }

    /// Send bytes to the phone, retrying — same phantom-send guarantee as the
    /// Android→Mac path: success only on a real 200. Shared by /send and /send-file.
    private func sendData(_ data: Data, filename: String) -> HTTPResponse {
        guard let peer = peerProvider() else {
            return .text(404, "phone not found on the network")
        }
        let mime = FileNaming.mimeType(for: filename)
        let semaphore = DispatchSemaphore(value: 0)
        var response = HTTPResponse.text(500, "unknown error")
        Task {
            defer { semaphore.signal() }
            var lastError: Error?
            for delay in [0.0, 0.5, 1.5] {
                if delay > 0 {
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                }
                do {
                    let result = try await authedClient.send(data: data, filename: filename,
                                                       mime: mime, host: peer.host, port: peer.port)
                    response = .json(200, result)
                    return
                } catch {
                    lastError = error
                }
            }
            response = .text(502, "send failed after retries: \(lastError?.localizedDescription ?? "")")
        }
        semaphore.wait()
        return response
    }
}
