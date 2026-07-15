import Foundation

public enum RD2MacError: Error, LocalizedError {
    case badStatus(Int, String)
    case notPaired
    case emptyResponse
    case peerNotFound
    /// A 200 whose body does not open with the pairing key: either tampering or
    /// an active MITM forging delivery — treated as NOT delivered (retry).
    case unauthenticatedResponse

    public var errorDescription: String? {
        switch self {
        case .badStatus(let code, let body): return "HTTP \(code): \(body)"
        case .notPaired: return "peer rejected the pairing token"
        case .emptyResponse: return "empty response body"
        case .peerNotFound: return "could not locate the RD2Mac peer on the network"
        case .unauthenticatedResponse: return "peer's 200 was not sealed with the pairing key"
        }
    }
}

public struct DownloadedFile {
    public let filename: String
    public let mime: String
    public let data: Data
}

/// HTTP client for the RD2Mac protocol. One shot per call; retry/rediscovery is
/// layered on top by `Transport` (which re-resolves the peer between attempts).
public struct HTTPClient {
    public var token: String?
    /// AES key of the paired peer; when set, request/response bodies are sealed.
    public var keyBytes: Data?
    public var peerName: String
    public var timeout: TimeInterval

    public init(peerName: String, token: String? = nil, keyBytes: Data? = nil,
                timeout: TimeInterval = 20) {
        self.peerName = peerName
        self.token = token
        self.keyBytes = keyBytes
        self.timeout = timeout
    }

    private func url(host: String, port: UInt16, path: String) -> URL {
        let bracketed = host.contains(":") ? "[\(host)]" : host // IPv6 literal
        return URL(string: "http://\(bracketed):\(port)\(path)")!
    }

    private func baseRequest(_ url: URL, method: String) -> URLRequest {
        var req = URLRequest(url: url, timeoutInterval: timeout)
        req.httpMethod = method
        req.setValue(peerName.rd2macHeaderEncoded, forHTTPHeaderField: RD2Mac.Header.peer)
        if let token, !token.isEmpty {
            req.setValue(token, forHTTPHeaderField: RD2Mac.Header.token)
        }
        return req
    }

    /// Seal/open the body with the paired key when present (no key ⇒ passthrough).
    private func seal(_ data: Data) throws -> Data {
        guard let k = keyBytes else { return data }
        return try RD2Crypto.seal(data, keyBytes: k)
    }
    private func open(_ data: Data) throws -> Data {
        guard let k = keyBytes else { return data }
        return try RD2Crypto.open(data, keyBytes: k)
    }

    // MARK: - GET /ping

    public func ping(host: String, port: UInt16) async -> PeerIdentity? {
        let req = baseRequest(url(host: host, port: port, path: RD2Mac.Path.ping), method: "GET")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONDecoder().decode(PeerIdentity.self, from: data)
    }

    // MARK: - POST /pair (handshake)

    /// Send our ECDH pair message; return the peer's reply message (its pubKey) on
    /// `200`, or nil on 403/failure. The caller derives the session from the reply.
    public func pair(_ message: PairMessage, host: String, port: UInt16) async -> PairMessage? {
        var req = baseRequest(url(host: host, port: port, path: RD2Mac.Path.pair), method: "POST")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONEncoder().encode(message)
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONDecoder().decode(PairMessage.self, from: data)
    }

    // MARK: - POST /receive

    public func send(data: Data, filename: String, mime: String,
                     host: String, port: UInt16) async throws -> ReceiveResult {
        var req = baseRequest(url(host: host, port: port, path: RD2Mac.Path.receive), method: "POST")
        req.setValue(HeaderCrypt.encode(filename, key: keyBytes),
                     forHTTPHeaderField: RD2Mac.Header.filename)
        req.setValue(mime, forHTTPHeaderField: "Content-Type")
        req.httpBody = try seal(data)

        let (respData, resp) = try await URLSession.shared.data(for: req)
        let status = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            if status == 401 { throw RD2MacError.notPaired }
            throw RD2MacError.badStatus(status, String(data: respData, encoding: .utf8) ?? "")
        }
        // The 200 only counts when its body opens with the pairing key — a bare
        // 200 could be an active MITM forging delivery (phantom-send, again).
        guard let plain = try? open(respData) else { throw RD2MacError.unauthenticatedResponse }
        return try JSONDecoder().decode(ReceiveResult.self, from: plain)
    }

    public func sendFile(_ fileURL: URL, host: String, port: UInt16) async throws -> ReceiveResult {
        let data = try Data(contentsOf: fileURL)
        let name = fileURL.lastPathComponent
        return try await send(data: data, filename: name,
                              mime: FileNaming.mimeType(for: name), host: host, port: port)
    }

    // MARK: - GET download (screenshot / media)

    public func download(path: String, host: String, port: UInt16) async throws -> DownloadedFile {
        let req = baseRequest(url(host: host, port: port, path: path), method: "GET")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw RD2MacError.emptyResponse }
        guard http.statusCode == 200 else {
            throw RD2MacError.badStatus(http.statusCode, String(data: data, encoding: .utf8) ?? "")
        }
        let filename = http.value(forHTTPHeaderField: RD2Mac.Header.filename)
            .map { HeaderCrypt.decode($0, key: keyBytes) } ?? "rd2mac-download"
        let mime = http.value(forHTTPHeaderField: "Content-Type") ?? "application/octet-stream"
        return DownloadedFile(filename: filename, mime: mime, data: try open(data))
    }
}
