import Foundation
import Network

/// Parsed HTTP request. Header keys are lowercased.
public struct HTTPRequest {
    public let method: String
    public let path: String
    public let query: [String: String]
    public let headers: [String: String]
    public let body: Data

    public func header(_ name: String) -> String? { headers[name.lowercased()] }
}

/// HTTP response to serialize back to the client.
public struct HTTPResponse {
    public var status: Int
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status
        self.headers = headers
        self.body = body
    }

    public static func json(_ status: Int, _ object: Encodable) -> HTTPResponse {
        let data = (try? JSONEncoder().encode(AnyEncodable(object))) ?? Data()
        return HTTPResponse(status: status,
                            headers: ["Content-Type": "application/json"],
                            body: data)
    }

    public static func text(_ status: Int, _ string: String) -> HTTPResponse {
        HTTPResponse(status: status,
                     headers: ["Content-Type": "text/plain; charset=utf-8"],
                     body: Data(string.utf8))
    }
}

private struct AnyEncodable: Encodable {
    let value: Encodable
    init(_ value: Encodable) { self.value = value }
    func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
}

/// Minimal, dependency-free HTTP/1.1 server over Network.framework.
///
/// Designed for the controlled RD2Mac protocol: single-shot requests with an
/// explicit `Content-Length` (no chunked encoding, `Connection: close`). The
/// `handler` runs on the server's serial queue and returns synchronously — the
/// `POST /receive` handler writes the file to disk *before* returning, which is
/// what makes a `200` a real delivery guarantee.
public final class HTTPServer {
    public typealias Handler = (HTTPRequest) -> HTTPResponse

    /// Bodies are held fully in memory (2-3 copies at peak with sealing); refuse
    /// anything bigger with a clean 413 (PROTOCOL.md) instead of ballooning.
    public static let maxBodyBytes = 4 * 1024 * 1024 * 1024 as Int64
    /// No legitimate RD2Mac request carries more than a few hundred header bytes.
    static let maxHeaderBytes = 64 * 1024

    private let requestedPort: UInt16
    private let loopbackOnly: Bool
    private let handler: Handler
    /// Cut a connection that has gone silent mid-request, so a stalled client
    /// can't pin its buffer forever. Reset on every received chunk.
    private let idleTimeout: TimeInterval
    private let queue = DispatchQueue(label: "net.simioni.rd2mac.httpserver")
    private var listener: NWListener?
    private var connCounter = 0

    /// The actually-bound port (differs from `requestedPort` when 0 was requested).
    public private(set) var boundPort: UInt16?

    /// - Parameter loopbackOnly: bind to 127.0.0.1 only. Used by the control
    ///   server so the CLI (and only local processes) can drive pull/send.
    public init(port: UInt16, loopbackOnly: Bool = false, idleTimeout: TimeInterval = 60,
                handler: @escaping Handler) {
        self.requestedPort = port
        self.loopbackOnly = loopbackOnly
        self.idleTimeout = idleTimeout
        self.handler = handler
    }

    public func start() throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let nwPort = NWEndpoint.Port(rawValue: requestedPort) ?? .any
        // For loopback-only, the bind host+port come from requiredLocalEndpoint;
        // passing `on:` as well is rejected with EINVAL (port specified twice).
        let listener: NWListener
        if loopbackOnly {
            params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: nwPort)
            listener = try NWListener(using: params)
        } else {
            listener = try NWListener(using: params, on: nwPort)
        }
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.boundPort = listener.port?.rawValue
            }
        }
        listener.newConnectionHandler = { [weak self] conn in
            self?.accept(conn)
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    public func stop() {
        listener?.cancel()
        listener = nil
    }

    // MARK: - Connection handling

    private func accept(_ conn: NWConnection) {
        // One queue per connection: handlers run concurrently, so a long file
        // transfer no longer blocks /ping and /clipboard (the Android side's
        // thread pool always behaved this way).
        connCounter += 1
        let connQueue = DispatchQueue(label: "net.simioni.rd2mac.conn.\(connCounter)")
        conn.start(queue: connQueue)
        receiveLoop(conn, buffer: Data(), queue: connQueue)
    }

    private func receiveLoop(_ conn: NWConnection, buffer: Data, queue connQueue: DispatchQueue) {
        // (Re)arm the idle watchdog: a client that stops sending mid-request is
        // dropped instead of holding the buffer until process exit.
        let watchdog = DispatchWorkItem { conn.cancel() }
        connQueue.asyncAfter(deadline: .now() + idleTimeout, execute: watchdog)

        conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { [weak self] data, _, isComplete, error in
            watchdog.cancel()
            guard let self else { conn.cancel(); return }
            var buf = buffer
            if let data, !data.isEmpty { buf.append(data) }

            if error != nil { conn.cancel(); return }

            switch HTTPServer.parse(buf) {
            case .done(let request):
                let response = self.handler(request)
                self.send(response, on: conn)
            case .bad(let reason):
                self.send(.text(400, "Bad Request: \(reason)"), on: conn)
            case .tooLarge:
                self.send(.text(413, "payload exceeds \(HTTPServer.maxBodyBytes) bytes"), on: conn)
            case .need:
                if isComplete { conn.cancel() } // closed mid-request
                else { self.receiveLoop(conn, buffer: buf, queue: connQueue) }
            }
        }
    }

    private func send(_ response: HTTPResponse, on conn: NWConnection) {
        let data = HTTPServer.serialize(response)
        conn.send(content: data, completion: .contentProcessed { _ in conn.cancel() })
    }

    // MARK: - Parsing

    enum ParseResult { case need, done(HTTPRequest), bad(String), tooLarge }

    static func parse(_ buffer: Data) -> ParseResult {
        let separator = Data("\r\n\r\n".utf8)
        guard let headerEnd = buffer.range(of: separator) else {
            // No terminator yet: keep reading, but never buffer headers unbounded.
            return buffer.count > maxHeaderBytes ? .bad("header block too large") : .need
        }

        let headerData = buffer.subdata(in: buffer.startIndex..<headerEnd.lowerBound)
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            return .bad("headers not UTF-8")
        }
        let lines = headerText.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return .bad("empty") }
        let parts = requestLine.split(separator: " ", maxSplits: 2).map(String.init)
        guard parts.count >= 2 else { return .bad("malformed request line") }

        let method = parts[0].uppercased()
        let target = parts[1]
        let (path, query) = splitTarget(target)

        var headers: [String: String] = [:]
        for line in lines.dropFirst() where !line.isEmpty {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            headers[key] = value
        }

        // Parse as Int64 first: a declared length beyond the cap is refused
        // before any buffering — the whole body is held in memory.
        let declared = Int64(headers["content-length"] ?? "0") ?? 0
        if declared < 0 { return .bad("negative Content-Length") }
        if declared > maxBodyBytes { return .tooLarge }

        let contentLength = Int(declared)
        let bodyStart = headerEnd.upperBound
        let available = buffer.distance(from: bodyStart, to: buffer.endIndex)
        if available < contentLength { return .need }

        let bodyEnd = buffer.index(bodyStart, offsetBy: contentLength)
        let body = buffer.subdata(in: bodyStart..<bodyEnd)
        return .done(HTTPRequest(method: method, path: path, query: query,
                                 headers: headers, body: body))
    }

    private static func splitTarget(_ target: String) -> (String, [String: String]) {
        guard let q = target.firstIndex(of: "?") else { return (target, [:]) }
        let path = String(target[..<q])
        let queryString = String(target[target.index(after: q)...])
        var query: [String: String] = [:]
        for pair in queryString.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1)
            let key = String(kv[0]).removingPercentEncoding ?? String(kv[0])
            let value = kv.count > 1 ? (String(kv[1]).removingPercentEncoding ?? String(kv[1])) : ""
            query[key] = value
        }
        return (path, query)
    }

    // MARK: - Serialization

    static func serialize(_ response: HTTPResponse) -> Data {
        var headers = response.headers
        headers["Content-Length"] = String(response.body.count)
        headers["Connection"] = "close"
        var head = "HTTP/1.1 \(response.status) \(reasonPhrase(response.status))\r\n"
        for (key, value) in headers { head += "\(key): \(value)\r\n" }
        head += "\r\n"
        var out = Data(head.utf8)
        out.append(response.body)
        return out
    }

    static func reasonPhrase(_ status: Int) -> String {
        switch status {
        case 200: return "OK"
        case 204: return "No Content"
        case 400: return "Bad Request"
        case 401: return "Unauthorized"
        case 403: return "Forbidden"
        case 404: return "Not Found"
        case 413: return "Payload Too Large"
        case 500: return "Internal Server Error"
        default: return "Status"
        }
    }
}
