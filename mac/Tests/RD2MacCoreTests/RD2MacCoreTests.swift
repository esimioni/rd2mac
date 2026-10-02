import XCTest
import CryptoKit
@testable import RD2MacCore

final class ProtocolTests: XCTestCase {

    func testSanitizeStripsPathTraversal() {
        XCTAssertEqual(FileNaming.sanitize("../../etc/passwd"), "passwd")
        XCTAssertEqual(FileNaming.sanitize("a/b/c.png"), "c.png")
        XCTAssertEqual(FileNaming.sanitize("normal.jpg"), "normal.jpg")
        XCTAssertFalse(FileNaming.sanitize("..").contains(".."))
    }

    func testUniqueDestinationDedups() throws {
        let dir = try makeTempDir()
        defer { try? FileManager.default.removeItem(at: dir) }

        let first = FileNaming.uniqueDestination(for: "a.png", in: dir)
        XCTAssertEqual(first.lastPathComponent, "a.png")
        try Data("x".utf8).write(to: first)

        let second = FileNaming.uniqueDestination(for: "a.png", in: dir)
        XCTAssertEqual(second.lastPathComponent, "a (2).png")
    }

    func testHeaderEncodingRoundTrip() {
        let name = "Screenshot 2026-07-07 12:30 (1).png"
        XCTAssertEqual(name.rd2macHeaderEncoded.rd2macHeaderDecoded, name)
    }

    /// Fixed vector shared with the Android `HeaderCodecTest` — the two platforms
    /// MUST produce identical output so filenames survive the round trip.
    func testHeaderEncodingFixedVector() {
        XCTAssertEqual("A1 b.png".rd2macHeaderEncoded, "A1%20b%2Epng")
        XCTAssertEqual("A1%20b%2Epng".rd2macHeaderDecoded, "A1 b.png")
    }

    // MARK: HeaderCrypt (S1) — mirror of the Kotlin HeaderCryptTest

    private let cryptKey = Data(repeating: 0x2A, count: 32)

    func testHeaderCryptSealedRoundTrip() {
        let name = "Relatório final (cópia) 2026.pdf"
        let encoded = HeaderCrypt.encode(name, key: cryptKey)
        XCTAssertFalse(encoded.contains("Relat"))   // sniffer with the token learns nothing
        XCTAssertEqual(HeaderCrypt.decode(encoded, key: cryptKey), name)
    }

    func testHeaderCryptWithoutKeyIsPlainEncoding() {
        let name = "a b.png"
        XCTAssertEqual(HeaderCrypt.encode(name, key: nil), name.rd2macHeaderEncoded)
        XCTAssertEqual(HeaderCrypt.decode(name.rd2macHeaderEncoded, key: nil), name)
    }

    /// A plain-encoded value arriving where a key exists (loopback hop) must
    /// still decode — never a hard failure over a filename.
    func testHeaderCryptDecodeToleratesPlainValue() {
        let name = "doc.pdf"
        XCTAssertEqual(HeaderCrypt.decode(name.rd2macHeaderEncoded, key: cryptKey), name)
    }

    // MARK: ClipboardEchoGuard — phone POST must not bounce back to the phone

    func testEchoGuardServesGenuineChange() {
        XCTAssertTrue(ClipboardEchoGuard.shouldServe(version: 8, since: 7, lastInbound: nil))
        XCTAssertTrue(ClipboardEchoGuard.shouldServe(version: 9, since: 7, lastInbound: 8))
    }

    func testEchoGuardSuppressesUnchanged() {
        XCTAssertFalse(ClipboardEchoGuard.shouldServe(version: 7, since: 7, lastInbound: nil))
    }

    /// The version created by the phone's own POST /clipboard: serving it back
    /// would loop the phone's content into its own paste → listener → push → ∞.
    func testEchoGuardSuppressesPhoneOwnContent() {
        XCTAssertFalse(ClipboardEchoGuard.shouldServe(version: 8, since: 7, lastInbound: 8))
    }
}

final class PullArgsTests: XCTestCase {

    func testScreenshotCountDefaultsAndClamps() {
        XCTAssertEqual(PullArgs.count([]), 1)         // default
        XCTAssertEqual(PullArgs.count(["3"]), 3)
        XCTAssertEqual(PullArgs.count(["0"]), 1)      // never pull zero
        XCTAssertEqual(PullArgs.count(["-2"]), 1)     // never negative
        XCTAssertEqual(PullArgs.count(["abc"]), 1)    // garbage → 1
    }

    func testPullTypeAndCount() {
        XCTAssertEqual(PullArgs.parse([]).type, "image")     // default type
        XCTAssertEqual(PullArgs.parse([]).count, 1)
        XCTAssertEqual(PullArgs.parse(["video"]).type, "video")
        XCTAssertEqual(PullArgs.parse(["video"]).count, 1)
        XCTAssertEqual(PullArgs.parse(["3"]).type, "image")  // bare count → image
        XCTAssertEqual(PullArgs.parse(["3"]).count, 3)
        let img = PullArgs.parse(["image", "2"])
        XCTAssertEqual(img.type, "image"); XCTAssertEqual(img.count, 2)
        let vid = PullArgs.parse(["video", "4"])
        XCTAssertEqual(vid.type, "video"); XCTAssertEqual(vid.count, 4)
        XCTAssertEqual(PullArgs.parse(["bogus"]).type, "image")  // unknown type → image
        XCTAssertEqual(PullArgs.parse(["bogus"]).count, 1)
    }
}

final class HTTPParseTests: XCTestCase {

    func testParseNeedsMoreWhenHeadersIncomplete() {
        let partial = Data("GET /ping HTTP/1.1\r\nHost: x".utf8)
        guard case .need = HTTPServer.parse(partial) else {
            return XCTFail("expected .need")
        }
    }

    func testParseGET() {
        let raw = Data("GET /media/latest?type=video HTTP/1.1\r\nHost: x\r\n\r\n".utf8)
        guard case .done(let req) = HTTPServer.parse(raw) else {
            return XCTFail("expected .done")
        }
        XCTAssertEqual(req.method, "GET")
        XCTAssertEqual(req.path, "/media/latest")
        XCTAssertEqual(req.query["type"], "video")
    }

    /** B2: a declared Content-Length beyond the cap must be refused before any
     *  buffering — the whole body is held in memory, so honoring an absurd
     *  declaration is a remote OOM. */
    func testParseRefusesOversizedContentLength() {
        let raw = Data("POST /receive HTTP/1.1\r\nContent-Length: 999999999999999\r\n\r\n".utf8)
        guard case .tooLarge = HTTPServer.parse(raw) else {
            return XCTFail("expected .tooLarge for a Content-Length beyond the cap")
        }
    }

    func testParseRefusesNegativeContentLength() {
        let raw = Data("POST /receive HTTP/1.1\r\nContent-Length: -5\r\n\r\n".utf8)
        guard case .bad = HTTPServer.parse(raw) else {
            return XCTFail("expected .bad for a negative Content-Length")
        }
    }

    /** A header block that never ends must be cut off, not buffered forever. */
    func testParseRefusesHugeHeaderBlock() {
        var raw = Data("GET /x HTTP/1.1\r\n".utf8)
        raw.append(Data(repeating: UInt8(ascii: "A"), count: 80 * 1024)) // no CRLFCRLF
        guard case .bad = HTTPServer.parse(raw) else {
            return XCTFail("expected .bad once the header block exceeds the cap")
        }
    }

    func testParsePOSTWaitsForFullBody() {
        let headers = "POST /receive HTTP/1.1\r\nContent-Length: 5\r\n\r\n"
        // Only 3 of 5 body bytes present.
        let short = Data((headers + "abc").utf8)
        guard case .need = HTTPServer.parse(short) else {
            return XCTFail("expected .need until body complete")
        }
        let full = Data((headers + "abcde").utf8)
        guard case .done(let req) = HTTPServer.parse(full) else {
            return XCTFail("expected .done")
        }
        XCTAssertEqual(req.body, Data("abcde".utf8))
    }

    func testSendTargetSimplePathRoundTripsThroughParser() {
        let target = ControlService.sendTarget(forPath: "/tmp/photo.jpg")
        let raw = Data("POST \(target) HTTP/1.1\r\nContent-Length: 0\r\n\r\n".utf8)
        guard case .done(let req) = HTTPServer.parse(raw) else {
            return XCTFail("expected .done")
        }
        XCTAssertEqual(req.path, "/send")
        XCTAssertEqual(req.query["path"], "/tmp/photo.jpg")
    }

    func testSendTargetHostilePathRoundTripsThroughParser() {
        // Space, &, =, +, ? and UTF-8 must all survive the query parser.
        let nasty = "/Users/e d/Down loads/relatório & contas=final+v2 (cópia)?.pdf"
        let target = ControlService.sendTarget(forPath: nasty)
        let raw = Data("POST \(target) HTTP/1.1\r\nContent-Length: 0\r\n\r\n".utf8)
        guard case .done(let req) = HTTPServer.parse(raw) else {
            return XCTFail("expected .done")
        }
        XCTAssertEqual(req.path, "/send")
        XCTAssertEqual(req.query["path"], nasty)
    }

    func testSerializeSetsContentLengthAndClose() {
        let resp = HTTPResponse.text(200, "hi")
        let out = String(data: HTTPServer.serialize(resp), encoding: .utf8)!
        XCTAssertTrue(out.hasPrefix("HTTP/1.1 200 OK\r\n"))
        XCTAssertTrue(out.contains("Content-Length: 2\r\n"))
        XCTAssertTrue(out.contains("Connection: close\r\n"))
        XCTAssertTrue(out.hasSuffix("\r\n\r\nhi"))
    }
}

final class RouterTests: XCTestCase {

    private func makeRouter(paired: PairedPeer? = nil, dir: URL,
                            onPair: @escaping (PairMessage) -> PairMessage? = { _ in nil },
                            clipboardGet: @escaping (Int) -> ClipboardPayload? = { _ in nil },
                            clipboardPut: @escaping (ClipboardPayload) -> Bool = { _ in false },
                            proximityPut: @escaping (ProximityReport) -> Void = { _ in })
        -> (HTTPRequest) -> HTTPResponse {
        let identity = PeerIdentity(name: "TestMac", platform: .macos)
        return makeMacRouter(config: ServerConfig(identity: identity, downloadsDir: dir,
                                                  pairing: { paired }, onPair: onPair,
                                                  clipboardGet: clipboardGet, clipboardPut: clipboardPut,
                                                  proximityPut: proximityPut))
    }

    private func request(_ method: String, _ path: String,
                         headers: [String: String] = [:], body: Data = Data()) -> HTTPRequest {
        var lowered: [String: String] = [:]
        for (k, v) in headers { lowered[k.lowercased()] = v }
        return HTTPRequest(method: method, path: path, query: [:], headers: lowered, body: body)
    }

    private let testKey = Data(repeating: 0x2A, count: 32)
    private func paired(token: String) -> PairedPeer {
        PairedPeer(id: "phone", name: "Phone", host: "", port: 53318,
                   token: token, key: testKey.base64EncodedString())
    }

    func testPingReturnsIdentity() throws {   // /ping stays open (health + discovery)
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let resp = makeRouter(dir: dir)(request("GET", "/ping"))
        XCTAssertEqual(resp.status, 200)
        let identity = try JSONDecoder().decode(PeerIdentity.self, from: resp.body)
        XCTAssertEqual(identity.name, "TestMac")
        XCTAssertEqual(identity.platform, .macos)
    }

    func testReceivePersistsWhenAuthorized() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let payload = Data("hello world".utf8)
        let sealed = try RD2Crypto.seal(payload, keyBytes: testKey)
        let resp = makeRouter(paired: paired(token: "tok"), dir: dir)(request(
            "POST", "/receive",
            headers: [RD2Mac.Header.filename: HeaderCrypt.encode("note.txt", key: testKey),
                      RD2Mac.Header.token: "tok"],
            body: sealed))

        XCTAssertEqual(resp.status, 200)
        // B3: the 200 body must be sealed with the pairing key — a plaintext body
        // is indistinguishable from an active MITM's forged confirmation.
        XCTAssertThrowsError(try JSONDecoder().decode(ReceiveResult.self, from: resp.body),
                             "response body must not be plaintext JSON")
        let opened = try RD2Crypto.open(resp.body, keyBytes: testKey)
        let result = try JSONDecoder().decode(ReceiveResult.self, from: opened)
        XCTAssertEqual(result.bytes, payload.count)   // decrypted length
        // File exists on disk at the reported path (the persistence guarantee),
        // under the sealed filename (S1).
        XCTAssertTrue(result.saved.hasSuffix("note.txt"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: result.saved))
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: result.saved)), payload)
    }

    func testReceiveRejectedWhenUnpaired() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        // No pairing → the server accepts nothing, even with a token (hotel-safety).
        let resp = makeRouter(dir: dir)(request("POST", "/receive",
            headers: [RD2Mac.Header.token: "whatever"], body: Data("x".utf8)))
        XCTAssertEqual(resp.status, 401)
    }

    func testReceiveRejectedWithWrongToken() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let resp = makeRouter(paired: paired(token: "right"), dir: dir)(request(
            "POST", "/receive", headers: [RD2Mac.Header.token: "wrong"], body: Data("x".utf8)))
        XCTAssertEqual(resp.status, 401)
    }

    func testPairAcceptedReturnsReplyMessage() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let reply = PairMessage(id: "mac", name: "Mac", pubKey: "REPLYPUB")
        var seen: PairMessage?
        let router = makeRouter(dir: dir, onPair: { seen = $0; return reply })
        let body = try JSONEncoder().encode(PairMessage(id: "p", name: "Phone", pubKey: "PUB"))
        let resp = router(request("POST", "/pair", body: body))
        XCTAssertEqual(resp.status, 200)
        XCTAssertEqual(seen, PairMessage(id: "p", name: "Phone", pubKey: "PUB"))
        XCTAssertEqual(try JSONDecoder().decode(PairMessage.self, from: resp.body), reply)
    }

    func testPairRejectedReturns403() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let router = makeRouter(dir: dir, onPair: { _ in nil })
        let body = try JSONEncoder().encode(PairMessage(id: "other", name: "X", pubKey: "PUB"))
        let resp = router(request("POST", "/pair", body: body))
        XCTAssertEqual(resp.status, 403)
    }

    func testUnknownRoute404() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let resp = makeRouter(dir: dir)(request("GET", "/nope"))
        XCTAssertEqual(resp.status, 404)
    }

    // MARK: clipboard

    func testClipboardGetServesPayloadWhenAuthorized() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let payload = ClipboardPayload(version: 7, contentType: "text/plain; charset=utf-8",
                                       data: Data("hi mac".utf8))
        let router = makeRouter(paired: paired(token: "tok"), dir: dir,
                                clipboardGet: { since in since == 7 ? nil : payload })
        let resp = router(request("GET", "/clipboard", headers: [RD2Mac.Header.token: "tok"]))
        XCTAssertEqual(resp.status, 200)
        XCTAssertEqual(try RD2Crypto.open(resp.body, keyBytes: testKey), Data("hi mac".utf8))
        XCTAssertEqual(resp.headers[RD2Mac.Header.clipVersion], "7")
    }

    func testClipboardGet204WhenUnchanged() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let router = makeRouter(paired: paired(token: "tok"), dir: dir, clipboardGet: { _ in nil })
        let resp = router(request("GET", "/clipboard", headers: [RD2Mac.Header.token: "tok"]))
        XCTAssertEqual(resp.status, 204)
    }

    func testClipboardRejectedWithoutToken() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let router = makeRouter(paired: paired(token: "tok"), dir: dir,
                                clipboardGet: { _ in ClipboardPayload(version: 1, contentType: "text/plain", data: Data()) })
        XCTAssertEqual(router(request("GET", "/clipboard")).status, 401) // no token
    }

    func testClipboardPostAcceptedWhenReceiving() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        var received: ClipboardPayload?
        let router = makeRouter(paired: paired(token: "tok"), dir: dir,
                                clipboardPut: { received = $0; return true })
        let sealed = try RD2Crypto.seal(Data("from phone".utf8), keyBytes: testKey)
        let resp = router(request("POST", "/clipboard",
                                  headers: [RD2Mac.Header.token: "tok", "Content-Type": "text/plain"],
                                  body: sealed))
        XCTAssertEqual(resp.status, 200)
        XCTAssertEqual(received?.data, Data("from phone".utf8))
    }

    func testClipboardPost403WhenReceivingOff() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let router = makeRouter(paired: paired(token: "tok"), dir: dir, clipboardPut: { _ in false })
        let sealed = try RD2Crypto.seal(Data("x".utf8), keyBytes: testKey)
        let resp = router(request("POST", "/clipboard",
                                  headers: [RD2Mac.Header.token: "tok"], body: sealed))
        XCTAssertEqual(resp.status, 403)
    }

    // MARK: proximity

    func testProximityReportAcceptedAndDelivered() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        var got: ProximityReport?
        let router = makeRouter(paired: paired(token: "tok"), dir: dir, proximityPut: { got = $0 })
        let sealed = try RD2Crypto.seal(Data(#"{"enabled":true,"near":true}"#.utf8), keyBytes: testKey)
        let resp = router(request("POST", "/proximity",
                                  headers: [RD2Mac.Header.token: "tok"], body: sealed))
        XCTAssertEqual(resp.status, 200)
        XCTAssertEqual(got, ProximityReport(enabled: true, near: true))
    }

    func testProximityRejectedWithoutToken() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        var called = false
        let router = makeRouter(paired: paired(token: "tok"), dir: dir, proximityPut: { _ in called = true })
        let sealed = try RD2Crypto.seal(Data(#"{"enabled":true,"near":true}"#.utf8), keyBytes: testKey)
        XCTAssertEqual(router(request("POST", "/proximity", body: sealed)).status, 401)
        XCTAssertFalse(called)
    }

    func testProximityBadBodyIs400() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let router = makeRouter(paired: paired(token: "tok"), dir: dir)
        // Unsealed garbage: must fail decrypt/decode, never reach the callback.
        let resp = router(request("POST", "/proximity",
                                  headers: [RD2Mac.Header.token: "tok"],
                                  body: Data("not sealed json".utf8)))
        XCTAssertEqual(resp.status, 400)
    }
}

/// End-to-end over a real TCP socket on localhost: HTTPServer + HTTPClient.
final class RoundTripTests: XCTestCase {

    func testPingAndReceiveOverLocalhost() async throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let identity = PeerIdentity(name: "LoopMac", platform: .macos)
        let key = Data(repeating: 0x2A, count: 32)
        let paired = PairedPeer(id: "phone", name: "Phone", host: "", port: 0, token: "tok",
                                key: key.base64EncodedString())
        let router = makeMacRouter(config: ServerConfig(identity: identity, downloadsDir: dir,
                                                        pairing: { paired }))

        let server = HTTPServer(port: 0, handler: router)   // ephemeral port
        try server.start()
        defer { server.stop() }
        let port = try await waitForBoundPort(server)

        // Token authorizes; the matching key seals the body (server decrypts it).
        let client = HTTPClient(peerName: "LoopClient", token: "tok", keyBytes: key)

        // /ping
        let pong = await client.ping(host: "127.0.0.1", port: port)
        XCTAssertEqual(pong?.name, "LoopMac")

        // /receive with a non-trivial payload (exercises TCP framing across chunks)
        let payload = Data(repeating: 0xAB, count: 300_000)
        let result = try await client.send(data: payload, filename: "blob.bin",
                                           mime: "application/octet-stream",
                                           host: "127.0.0.1", port: port)
        XCTAssertEqual(result.bytes, payload.count)
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: result.saved)), payload)
    }

    /** B2: a client that connects and goes silent must be dropped by the idle
     *  watchdog instead of pinning its connection until process exit. */
    func testIdleConnectionIsDropped() async throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let server = HTTPServer(
            port: 0, idleTimeout: 0.3,
            handler: makeMacRouter(config: ServerConfig(
                identity: PeerIdentity(name: "T", platform: .macos), downloadsDir: dir)))
        try server.start()
        defer { server.stop() }
        let port = try await waitForBoundPort(server)

        // Raw POSIX socket: connect, send nothing, wait for the server to close.
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(fd, 0)
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        XCTAssertEqual(rc, 0)

        // read() returns 0 (EOF) when the server cancels; block up to 3s.
        var tv = timeval(tv_sec: 3, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var byte: UInt8 = 0
        let n = read(fd, &byte, 1)
        XCTAssertEqual(n, 0, "expected EOF from the idle watchdog, got read()=\(n)")
    }

    func testLoopbackServerBindsAndServes() async throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let identity = PeerIdentity(name: "Loopback", platform: .macos)
        let server = HTTPServer(
            port: 0, loopbackOnly: true,
            handler: makeMacRouter(config: ServerConfig(identity: identity, downloadsDir: dir)))
        try server.start()
        defer { server.stop() }
        // Fails if the loopback bind regressed (EINVAL → boundPort never set).
        let port = try await waitForBoundPort(server)

        let pong = await HTTPClient(peerName: "c").ping(host: "127.0.0.1", port: port)
        XCTAssertEqual(pong?.name, "Loopback")
    }

    private func waitForBoundPort(_ server: HTTPServer, timeout: TimeInterval = 3) async throws -> UInt16 {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let port = server.boundPort, port != 0 { return port }
            try await Task.sleep(nanoseconds: 30_000_000) // 30ms
        }
        throw RD2MacError.peerNotFound
    }
}

/// Drives the control API's pull against a fake "phone" HTTPServer.
final class ControlServiceTests: XCTestCase {

    private func controlRequest(_ path: String) -> HTTPRequest {
        HTTPRequest(method: "POST", path: path, query: [:], headers: [:], body: Data())
    }

    func testPullScreenshotSavesFileFromPeer() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let phoneBytes = Data("fake screenshot bytes".utf8)
        let key = Data(repeating: 0x2A, count: 32)
        let pairedPhone = PairedPeer(id: "s23", name: "S23", host: "", port: 0,
                                     token: "tok", key: key.base64EncodedString())

        // Fake phone serving /screenshot/latest the way the real one does:
        // sealed body + sealed filename header (S1).
        let phone = HTTPServer(port: 0) { request in
            guard request.method == "GET", request.path == RD2Mac.Path.screenshotLatest else {
                return .text(404, "nope")
            }
            guard let sealed = try? RD2Crypto.seal(phoneBytes, keyBytes: key) else {
                return .text(500, "seal failed")
            }
            return HTTPResponse(
                status: 200,
                headers: ["Content-Type": "image/png",
                          RD2Mac.Header.filename: HeaderCrypt.encode("Screenshot_2026.png", key: key)],
                body: sealed)
        }
        try phone.start(); defer { phone.stop() }
        let phonePort = try XCTUnwrap(waitForBoundPortSync(phone))

        let peer = ResolvedPeer(name: "S23", host: "127.0.0.1", port: phonePort, platform: .android)
        let control = ControlService(peerProvider: { peer },
                                     pairingProvider: { pairedPhone },
                                     client: HTTPClient(peerName: "Mac"),
                                     downloadsDir: dir)
        let router = control.makeRouter()

        // pull() blocks its thread on a semaphore, so drive it off the test thread.
        let done = expectation(description: "pull")
        var response: HTTPResponse!
        DispatchQueue.global().async {
            response = router(self.controlRequest("/pull-screenshot"))
            done.fulfill()
        }
        wait(for: [done], timeout: 10)

        XCTAssertEqual(response.status, 200)
        // pull now always returns a JSON array (one entry per saved file); a plain
        // pull-screenshot is an array of one.
        let results = try JSONDecoder().decode([ReceiveResult].self, from: response.body)
        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].bytes, phoneBytes.count)
        XCTAssertTrue(results[0].saved.contains("Screenshot_2026"))
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: results[0].saved)), phoneBytes)
    }

    /// Fake phone serving `available` screenshots indexed by `?skip=` (skip≥available
    /// → 404) — the phone side of a multi-item pull.
    private func multiScreenshotPhone(available: Int, key: Data, port: UInt16 = 0) -> HTTPServer {
        HTTPServer(port: port) { request in
            guard request.method == "GET", request.path == RD2Mac.Path.screenshotLatest else {
                return .text(404, "nope")
            }
            let skip = Int(request.query["skip"] ?? "0") ?? 0
            guard skip < available else { return .text(404, "no more") }
            let bytes = Data("screenshot #\(skip)".utf8)
            guard let sealed = try? RD2Crypto.seal(bytes, keyBytes: key) else { return .text(500, "seal") }
            return HTTPResponse(
                status: 200,
                headers: ["Content-Type": "image/png",
                          RD2Mac.Header.filename: HeaderCrypt.encode("Shot_\(skip).png", key: key)],
                body: sealed)
        }
    }

    private func runPull(_ query: [String: String], against phone: HTTPServer,
                         into dir: URL, key: Data) throws -> HTTPResponse {
        try runPull(query, phonePort: try XCTUnwrap(waitForBoundPortSync(phone)), into: dir, key: key)
    }

    private func runPull(_ query: [String: String], phonePort: UInt16,
                         into dir: URL, key: Data) throws -> HTTPResponse {
        let peer = ResolvedPeer(name: "S23", host: "127.0.0.1", port: phonePort, platform: .android)
        let pairedPhone = PairedPeer(id: "s23", name: "S23", host: "", port: 0,
                                     token: "tok", key: key.base64EncodedString())
        let control = ControlService(peerProvider: { peer }, pairingProvider: { pairedPhone },
                                     client: HTTPClient(peerName: "Mac"), downloadsDir: dir)
        let router = control.makeRouter()
        let done = expectation(description: "pull")
        var response: HTTPResponse!
        DispatchQueue.global().async {
            response = router(HTTPRequest(method: "POST", path: "/pull-screenshot",
                                          query: query, headers: [:], body: Data()))
            done.fulfill()
        }
        wait(for: [done], timeout: 10)
        return response
    }

    func testPullScreenshotCountSavesMostRecentN() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let key = Data(repeating: 0x2A, count: 32)
        let phone = multiScreenshotPhone(available: 3, key: key)
        try phone.start(); defer { phone.stop() }

        let response = try runPull(["count": "3"], against: phone, into: dir, key: key)

        XCTAssertEqual(response.status, 200)
        let results = try JSONDecoder().decode([ReceiveResult].self, from: response.body)
        XCTAssertEqual(results.count, 3)
        // Three distinct files on disk (deduped names), each the expected content.
        XCTAssertEqual(Set(results.map { $0.saved }).count, 3)
        for (skip, result) in results.enumerated() {
            XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: result.saved)),
                           Data("screenshot #\(skip)".utf8))
        }
    }

    func testPullStopsWhenPeerRunsOutOfItems() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let key = Data(repeating: 0x2A, count: 32)
        let phone = multiScreenshotPhone(available: 2, key: key)   // fewer than asked
        try phone.start(); defer { phone.stop() }

        let response = try runPull(["count": "5"], against: phone, into: dir, key: key)

        // Asking for 5 with only 2 available yields the 2 that exist, not an error.
        XCTAssertEqual(response.status, 200)
        let results = try JSONDecoder().decode([ReceiveResult].self, from: response.body)
        XCTAssertEqual(results.count, 2)
    }

    /// The first connection of a freshly installed Mac binary is refused by Local Network
    /// privacy for ~50 ms (measured 02/10/2026, right after 0.6 replaced the app): a pull
    /// that gave up on the first transport error failed after every update.
    func testPullRetriesATransportErrorBeforeGivingUp() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let key = Data(repeating: 0x2A, count: 32)
        let port = unusedLoopbackPort()              // nothing listens yet: the first try is refused
        let phone = multiScreenshotPhone(available: 1, key: key, port: port)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { try? phone.start() }
        defer { phone.stop() }

        let response = try runPull(["count": "1"], phonePort: port, into: dir, key: key)

        XCTAssertEqual(response.status, 200, String(decoding: response.body, as: UTF8.self))
        XCTAssertEqual(try JSONDecoder().decode([ReceiveResult].self, from: response.body).count, 1)
    }

    func testRunningOutOfItemsStopsAtOnceWithoutRetrying() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let key = Data(repeating: 0x2A, count: 32)
        let phone = multiScreenshotPhone(available: 1, key: key)
        try phone.start(); defer { phone.stop() }

        let started = Date()
        let response = try runPull(["count": "3"], against: phone, into: dir, key: key)

        // The 404 is the phone's "no more items", not a blip: no retry delay on it.
        XCTAssertLessThan(Date().timeIntervalSince(started), 0.4)
        XCTAssertEqual(try JSONDecoder().decode([ReceiveResult].self, from: response.body).count, 1)
    }

    func testPullReturns404WhenNoPeer() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let control = ControlService(peerProvider: { nil },
                                     client: HTTPClient(peerName: "Mac"),
                                     downloadsDir: dir)
        let response = control.makeRouter()(controlRequest("/pull-screenshot"))
        XCTAssertEqual(response.status, 404)
    }

    func testSendDeliversLocalFileToPeer() throws {
        let phoneDir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: phoneDir) }
        // Fake phone accepting POST /receive into phoneDir.
        let phone = HTTPServer(port: 0) { request in
            guard request.method == "POST", request.path == RD2Mac.Path.receive else {
                return .text(404, "nope")
            }
            let name = request.header(RD2Mac.Header.filename)?.rd2macHeaderDecoded ?? "f"
            guard let result = try? persist(request.body, preferredName: name, into: phoneDir) else {
                return .text(500, "save failed")
            }
            return .json(200, result)
        }
        try phone.start(); defer { phone.stop() }
        let phonePort = try XCTUnwrap(waitForBoundPortSync(phone))

        // A local file to send.
        let srcDir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: srcDir) }
        let src = srcDir.appendingPathComponent("hello.txt")
        try Data("send me".utf8).write(to: src)

        let peer = ResolvedPeer(name: "S23", host: "127.0.0.1", port: phonePort, platform: .android)
        let dl = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dl) }
        let control = ControlService(peerProvider: { peer },
                                     client: HTTPClient(peerName: "Mac"), downloadsDir: dl)
        let router = control.makeRouter()

        let done = expectation(description: "send")
        var response: HTTPResponse!
        DispatchQueue.global().async {
            response = router(HTTPRequest(method: "POST", path: "/send",
                                          query: ["path": src.path], headers: [:], body: Data()))
            done.fulfill()
        }
        wait(for: [done], timeout: 10)

        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(try Data(contentsOf: phoneDir.appendingPathComponent("hello.txt")),
                       Data("send me".utf8))
    }

    func testSendFileBytesDeliversToPeer() throws {
        let phoneDir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: phoneDir) }
        let phone = HTTPServer(port: 0) { request in
            guard request.method == "POST", request.path == RD2Mac.Path.receive else {
                return .text(404, "nope")
            }
            let name = request.header(RD2Mac.Header.filename)?.rd2macHeaderDecoded ?? "f"
            guard let result = try? persist(request.body, preferredName: name, into: phoneDir) else {
                return .text(500, "save failed")
            }
            return .json(200, result)
        }
        try phone.start(); defer { phone.stop() }
        let phonePort = try XCTUnwrap(waitForBoundPortSync(phone))

        let peer = ResolvedPeer(name: "S23", host: "127.0.0.1", port: phonePort, platform: .android)
        let dl = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dl) }
        let control = ControlService(peerProvider: { peer },
                                     client: HTTPClient(peerName: "Mac"), downloadsDir: dl)
        let router = control.makeRouter()

        let payload = Data("shared file bytes".utf8)
        let done = expectation(description: "send-file")
        var response: HTTPResponse!
        DispatchQueue.global().async {
            response = router(HTTPRequest(
                method: "POST", path: "/send-file", query: [:],
                headers: [RD2Mac.Header.filename.lowercased(): "doc.pdf".rd2macHeaderEncoded],
                body: payload))
            done.fulfill()
        }
        wait(for: [done], timeout: 10)

        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(try Data(contentsOf: phoneDir.appendingPathComponent("doc.pdf")), payload)
    }
}

/// Pairing: stable device id, persistence of the chosen peer, and the pure
/// selection of the active peer from the live list.
/// The browse-recovery rule (BACKLOG 0, 11/08/2026): a `NetServiceBrowser` can
/// stop delivering with no callback at all, so recovery can't be tested by an
/// E2E on a healthy network — these tests drive the clock instead, which is the
/// only way to prove the re-arm happens when nothing reports the failure.
final class BrowseKeepAliveTests: XCTestCase {

    private let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testRearmsOnScheduleEvenWithNoFailureReported() {
        var k = BrowseKeepAlive(now: t0, rearmEvery: 60, peerTTL: 150, debounce: 5)
        XCTAssertFalse(k.shouldRearm(now: t0.addingTimeInterval(59)))
        XCTAssertTrue(k.shouldRearm(now: t0.addingTimeInterval(60)))
        // The period restarts from the re-arm, not from t0.
        XCTAssertFalse(k.shouldRearm(now: t0.addingTimeInterval(119)))
        XCTAssertTrue(k.shouldRearm(now: t0.addingTimeInterval(120)))
    }

    func testForcedRearmIsImmediateButDebounced() {
        var k = BrowseKeepAlive(now: t0, rearmEvery: 60, peerTTL: 150, debounce: 5)
        // didNotSearch / wake / path change: act now, don't wait out the period.
        XCTAssertTrue(k.shouldRearm(now: t0.addingTimeInterval(6), forced: true))
        // A burst of path callbacks must not hammer mDNS.
        XCTAssertFalse(k.shouldRearm(now: t0.addingTimeInterval(7), forced: true))
        XCTAssertTrue(k.shouldRearm(now: t0.addingTimeInterval(11), forced: true))
    }

    func testAdvertiserPeriodIsSlowerThanTheBrowsePeriod() {
        // The advertiser reuses this rule with a 5-minute period: re-announcing
        // is what reaches a phone that never gets our replies, but a goodbye +
        // announce every minute would make everyone else's list flicker.
        var advertiser = BrowseKeepAlive(now: t0, rearmEvery: 300)
        XCTAssertFalse(advertiser.shouldRearm(now: t0.addingTimeInterval(60)))
        XCTAssertFalse(advertiser.shouldRearm(now: t0.addingTimeInterval(299)))
        XCTAssertTrue(advertiser.shouldRearm(now: t0.addingTimeInterval(300)))
        // A wake or a network change still re-announces at once.
        XCTAssertTrue(advertiser.shouldRearm(now: t0.addingTimeInterval(320), forced: true))
    }

    func testPeerThatSurvivesTwoRearmsWithoutAnsweringIsDropped() {
        let k = BrowseKeepAlive(now: t0, rearmEvery: 60, peerTTL: 150, debounce: 5)
        XCTAssertFalse(k.isStale(seen: t0, now: t0.addingTimeInterval(150)))
        XCTAssertTrue(k.isStale(seen: t0, now: t0.addingTimeInterval(151)))
    }

    func testSweepKeepsFreshPeersAndDropsStaleOnes() {
        let k = BrowseKeepAlive(now: t0, rearmEvery: 60, peerTTL: 150, debounce: 5)
        let fresh = ResolvedPeer(id: "a", name: "S23", host: "10.0.0.5", port: 53318, platform: .android)
        let gone = ResolvedPeer(id: "b", name: "Old", host: "10.0.0.9", port: 53318, platform: .android)
        let entries = ["S23": PeerBrowser.Entry(peer: fresh, seen: t0.addingTimeInterval(140)),
                       "Old": PeerBrowser.Entry(peer: gone, seen: t0)]
        let live = PeerBrowser.liveEntries(entries, now: t0.addingTimeInterval(160), policy: k)
        XCTAssertEqual(Array(live.keys), ["S23"])
    }
}

final class PairingTests: XCTestCase {

    private func makeStore() -> (PairingStore, String) {
        let suite = "rd2mac-test-\(UUID().uuidString)"
        return (PairingStore(defaults: UserDefaults(suiteName: suite)!), suite)
    }

    private func store(for suite: String) -> PairingStore {
        PairingStore(defaults: UserDefaults(suiteName: suite)!)
    }

    func testDeviceIdIsStableAndPersists() {
        let (s, suite) = makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let first = s.deviceId
        XCTAssertFalse(first.isEmpty)
        XCTAssertEqual(s.deviceId, first)                  // stable within an instance
        XCTAssertEqual(store(for: suite).deviceId, first)  // persists across instances
    }

    func testPairPersistsAndForget() {
        let (s, suite) = makeStore()
        defer { UserDefaults().removePersistentDomain(forName: suite) }
        let session = RD2Crypto.Session(keyBytes: Data(repeating: 0x01, count: 32),
                                        token: "tok", sas: "000000")
        s.pair(ResolvedPeer(id: "abc", name: "S23", host: "10.0.0.5", port: 53318, platform: .android),
               session: session)
        XCTAssertEqual(store(for: suite).pairedPeer,
                       PairedPeer(id: "abc", name: "S23", host: "10.0.0.5", port: 53318,
                                  token: "tok", key: session.keyBytes.base64EncodedString()))
        s.forget()
        XCTAssertNil(store(for: suite).pairedPeer)
    }

    func testActivePeerSelectsPairedFromList() {
        let a = ResolvedPeer(id: "1", name: "A", host: "10.0.0.1", port: 53318, platform: .android)
        let b = ResolvedPeer(id: "2", name: "B", host: "10.0.0.2", port: 53318, platform: .android)
        XCTAssertEqual(PeerSelection.activePeer(from: [a, b], pairedId: "2"), b)
        XCTAssertNil(PeerSelection.activePeer(from: [a, b], pairedId: "99")) // paired peer absent
        XCTAssertNil(PeerSelection.activePeer(from: [a, b], pairedId: nil))  // unpaired
        XCTAssertNil(PeerSelection.activePeer(from: [a, b], pairedId: ""))   // unpaired
    }

    func testAuthorizeRequiresPairingAndMatchingToken() {
        XCTAssertTrue(PairingAuth.authorize(pairedToken: "t", requestToken: "t"))
        XCTAssertFalse(PairingAuth.authorize(pairedToken: "t", requestToken: "x"))
        XCTAssertFalse(PairingAuth.authorize(pairedToken: "t", requestToken: nil))
        XCTAssertFalse(PairingAuth.authorize(pairedToken: nil, requestToken: "t")) // unpaired: reject all
        XCTAssertFalse(PairingAuth.authorize(pairedToken: "", requestToken: ""))   // unpaired
    }

    func testAcceptPairGuardsEstablishedPairing() {
        XCTAssertTrue(PairingAuth.acceptPair(currentId: nil, incomingId: "a"))  // free
        XCTAssertTrue(PairingAuth.acceptPair(currentId: "", incomingId: "a"))   // free
        XCTAssertTrue(PairingAuth.acceptPair(currentId: "a", incomingId: "a"))  // same peer re-pairs
        XCTAssertFalse(PairingAuth.acceptPair(currentId: "a", incomingId: "b")) // hijack attempt
    }
}

/// End-to-end crypto: ECDH agreement, session derivation, and AES-GCM seal/open.
/// The fixed-key vector is mirrored in the Android `RD2CryptoTest` — same inputs
/// MUST yield the same key/token/SAS, which is what proves cross-platform interop.
final class CryptoTests: XCTestCase {
    private let privARaw = Data(repeating: 0x11, count: 32)
    private let privBRaw = Data(repeating: 0x22, count: 32)

    func testAgreementMatchesAndDerivesSession() throws {
        let a = try P256.KeyAgreement.PrivateKey(rawRepresentation: privARaw)
        let b = try P256.KeyAgreement.PrivateKey(rawRepresentation: privBRaw)
        let pubA = RD2Crypto.publicKeyBase64(a)
        let pubB = RD2Crypto.publicKeyBase64(b)

        let sA = try RD2Crypto.agree(privateKey: a, peerPublicKeyBase64: pubB)
        let sB = try RD2Crypto.agree(privateKey: b, peerPublicKeyBase64: pubA)

        XCTAssertEqual(sA, sB)              // both sides derive the same session
        XCTAssertEqual(sA.token.count, 32) // 16 bytes as hex
        XCTAssertEqual(sA.sas.count, 6)
        // Emit the vector for the Android test to reproduce exactly.
        print("RD2VEC pubA=\(pubA)")
        print("RD2VEC pubB=\(pubB)")
        print("RD2VEC key=\(sA.keyBytes.map { String(format: "%02x", $0) }.joined())")
        print("RD2VEC token=\(sA.token)")
        print("RD2VEC sas=\(sA.sas)")
    }

    func testSealOpenRoundTrip() throws {
        let key = Data(repeating: 0xAB, count: 32)
        let msg = Data("secret payload — não vaza".utf8)
        let blob = try RD2Crypto.seal(msg, keyBytes: key)
        XCTAssertNotEqual(blob, msg)
        XCTAssertEqual(try RD2Crypto.open(blob, keyBytes: key), msg)
        XCTAssertThrowsError(try RD2Crypto.open(blob, keyBytes: Data(repeating: 0xCD, count: 32)))
    }
}

/// The CLI's loopback call into the app. A pull of a whole gallery runs for minutes and
/// the app answers only when it is over: the CLI gave up at 60 s and blamed the app for
/// not running, while the app kept pulling into ~/Downloads (measured 01/10/2026).
final class ControlClientTests: XCTestCase {

    private func startControl(_ handler: @escaping HTTPServer.Handler) throws -> (HTTPServer, ControlClient) {
        let server = HTTPServer(port: 0, loopbackOnly: true, handler: handler)
        try server.start()
        guard let port = waitForBoundPortSync(server) else { throw RD2MacError.peerNotFound }
        return (server, ControlClient(port: port))
    }

    func testTheAppsReplyComesBackWithStatusAndBody() async throws {
        let (server, client) = try startControl { _ in .text(502, "pull failed: no items") }
        defer { server.stop() }
        let outcome = await client.call("POST", "/pull-media?type=image&count=2", timeout: 5)
        XCTAssertEqual(outcome, .reply(status: 502, body: Data("pull failed: no items".utf8)))
        XCTAssertEqual(outcome.errorText, "pull failed: no items")
    }

    func testErrorTextNeverComesOutEmpty() {
        XCTAssertEqual(ControlClient.Outcome.reply(status: 500, body: Data()).errorText, "HTTP 500")
        XCTAssertTrue(ControlClient.Outcome.failed("connection lost").errorText.contains("connection lost"))
    }

    func testNothingListeningMeansTheAppIsNotRunning() async {
        let outcome = await ControlClient(port: unusedLoopbackPort()).call("GET", "/peer", timeout: 5)
        XCTAssertEqual(outcome, .notRunning)
        // "control server" = the Mac's side: the rd2mac memory's diagnosis keys on this text.
        XCTAssertEqual(outcome.errorText, "control server not responding — is the RD2Mac app running?")
    }

    func testAnAppStillWorkingIsNotReportedAsNotRunning() async throws {
        let (server, client) = try startControl { _ in
            Thread.sleep(forTimeInterval: 1)   // a pull still going when the caller gives up
            return .text(200, "[]")
        }
        defer { server.stop() }
        let outcome = await client.call("POST", "/pull-media?type=image&count=5000", timeout: 0.3)
        XCTAssertEqual(outcome, .timedOut)
        XCTAssertFalse(outcome.errorText.contains("is the RD2Mac app running"))
    }

    func testPullsAndSendsWaitForTheAppNotForAShortTimer() {
        // A whole gallery is thousands of items — hours, not the 60 s that cut it short.
        XCTAssertGreaterThanOrEqual(ControlClient.workTimeout, 24 * 60 * 60)
    }
}

/// Self-update: what the feed says, which version wins, when to look, when to swap.
final class UpdateFeedTests: XCTestCase {

    /// The shape GitHub returned for v0.5 (01/10/2026), trimmed to what the apps read.
    private func feed(tag: String = "v0.5", asset: String = "RD2Mac.dmg",
                      digest: String? = "sha256:677ACCF633B905BEA7AE684632EF49FB3BF47ACEE8C4A0EF0E9FC87206F5B3CC",
                      prerelease: Bool = false) -> Data {
        let digestField = digest.map { "\"digest\": \"\($0)\"," } ?? ""
        return Data("""
        {"tag_name": "\(tag)", "name": "RD2Mac 0.5", "draft": false, "prerelease": \(prerelease),
         "assets": [
          {"name": "RD2Mac.apk", "size": 11056702,
           "digest": "sha256:9a493e80e61e4ed298dbaeaeb6e8de4140a1dc39bbdae413868d1da2786fd6ef",
           "browser_download_url": "https://github.com/esimioni/rd2mac/releases/download/v0.5/RD2Mac.apk"},
          {"name": "\(asset)", "size": 2232313, \(digestField)
           "browser_download_url": "https://github.com/esimioni/rd2mac/releases/download/v0.5/\(asset)"}
         ]}
        """.utf8)
    }

    func testReadsTheMacAssetOfTheLatestRelease() {
        XCTAssertEqual(UpdateFeed.parse(feed(), asset: UpdateFeed.macAsset), Release(
            version: "0.5",
            assetURL: URL(string: "https://github.com/esimioni/rd2mac/releases/download/v0.5/RD2Mac.dmg")!,
            size: 2232313,
            sha256: "677accf633b905bea7ae684632ef49fb3bf47acee8c4a0ef0e9fc87206f5b3cc"))
    }

    func testNoAssetNoPrereleaseNoGarbage() {
        XCTAssertNil(UpdateFeed.parse(feed(asset: "RD2Mac.zip"), asset: UpdateFeed.macAsset))
        XCTAssertNil(UpdateFeed.parse(feed(prerelease: true), asset: UpdateFeed.macAsset))
        XCTAssertNil(UpdateFeed.parse(Data("{\"message\": \"Not Found\"}".utf8), asset: UpdateFeed.macAsset))
        XCTAssertNil(UpdateFeed.parse(Data("<html>".utf8), asset: UpdateFeed.macAsset))
    }

    func testOnlyAWellFormedSha256DigestIsKept() {
        XCTAssertNil(UpdateFeed.parse(feed(digest: nil), asset: UpdateFeed.macAsset)?.sha256)
        XCTAssertNil(UpdateFeed.parse(feed(digest: "md5:0123"), asset: UpdateFeed.macAsset)?.sha256)
        XCTAssertNil(UpdateFeed.parse(feed(digest: "sha256:xyz"), asset: UpdateFeed.macAsset)?.sha256)
        XCTAssertEqual(UpdateFeed.parse(feed(tag: "0.7"), asset: UpdateFeed.macAsset)?.version, "0.7")
    }

    func testOnlyAHigherVersionIsNewer() {
        XCTAssertTrue(AppVersion.isNewer("0.6", than: "0.5"))
        XCTAssertTrue(AppVersion.isNewer("0.10", than: "0.9"))        // numeric, not text
        XCTAssertTrue(AppVersion.isNewer("1.0", than: "0.9.9"))
        XCTAssertFalse(AppVersion.isNewer("0.5", than: "0.5"))
        XCTAssertFalse(AppVersion.isNewer("0.6.0", than: "0.6"))
        XCTAssertFalse(AppVersion.isNewer("0.4", than: "0.5"))        // never a downgrade
    }

    func testAnUnreadableVersionNeverTriggersAnUpdate() {
        XCTAssertFalse(AppVersion.isNewer("0.6-beta", than: "0.5"))
        XCTAssertFalse(AppVersion.isNewer("", than: "0.5"))
        XCTAssertFalse(AppVersion.isNewer("1..0", than: "0.5"))
        XCTAssertFalse(AppVersion.isNewer("+1.0", than: "0.5"))
        XCTAssertFalse(AppVersion.isNewer("0.6", than: "dev"))
    }

    func testARejectedReleaseIsNotFetchedAgain() {
        XCTAssertTrue(UpdatePolicy.wants("0.6", installed: "0.5", rejected: nil))
        XCTAssertFalse(UpdatePolicy.wants("0.6", installed: "0.5", rejected: "0.6"))   // already turned down
        XCTAssertTrue(UpdatePolicy.wants("0.7", installed: "0.5", rejected: "0.6"))    // a new release is looked at
        XCTAssertFalse(UpdatePolicy.wants("0.5", installed: "0.5", rejected: nil))
    }

    func testOneCheckADay() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertTrue(UpdateSchedule.isDue(lastCheck: nil, now: now))
        XCTAssertFalse(UpdateSchedule.isDue(lastCheck: now.addingTimeInterval(-(24 * 3600 - 60)), now: now))
        XCTAssertTrue(UpdateSchedule.isDue(lastCheck: now.addingTimeInterval(-24 * 3600), now: now))
        // A clock set back behind the last check must not silence updates for good.
        XCTAssertTrue(UpdateSchedule.isDue(lastCheck: now.addingTimeInterval(3600), now: now))
    }
}

final class TransferActivityTests: XCTestCase {

    func testQuietOnlyWhenNothingMovedForTheQuietPeriod() {
        var now = Date(timeIntervalSince1970: 1_800_000_000)
        let activity = TransferActivity(now: { now })
        XCTAssertTrue(activity.isQuiet)                         // nothing ever moved
        activity.begin()
        XCTAssertFalse(activity.isQuiet)
        activity.begin()                                        // two at once
        activity.end()
        XCTAssertFalse(activity.isQuiet)                        // one still going
        activity.end()
        XCTAssertFalse(activity.isQuiet)                        // a batch's next file may follow
        now = now.addingTimeInterval(TransferActivity.quietPeriod - 1)
        XCTAssertFalse(activity.isQuiet)
        now = now.addingTimeInterval(1)
        XCTAssertTrue(activity.isQuiet)
    }

    func testATransferThatThrowsStillEnds() {
        var now = Date(timeIntervalSince1970: 1_800_000_000)
        let activity = TransferActivity(now: { now })
        XCTAssertThrowsError(try activity.track { throw RD2MacError.peerNotFound })
        now = now.addingTimeInterval(TransferActivity.quietPeriod)
        XCTAssertTrue(activity.isQuiet)                         // not stuck "in flight"
    }
}

final class TransferTrackingTests: XCTestCase {

    func testTheRequestLineIsReadBeforeTheBodyArrives() {
        XCTAssertNil(HTTPServer.requestLine(Data("POST /rece".utf8)))
        let line = HTTPServer.requestLine(Data("post /receive?x=1 HTTP/1.1\r\nContent-Le".utf8))
        XCTAssertEqual(line?.method, "POST")
        XCTAssertEqual(line?.path, "/receive")
    }

    func testOnlyATrackedRequestIsATransferAndOnlyUntilItsReplyIsSent() throws {
        var now = Date(timeIntervalSince1970: 1_800_000_000)
        let activity = TransferActivity(now: { now })
        let lock = NSLock()
        var quietDuring: [String: Bool] = [:]
        let server = HTTPServer(port: 0, loopbackOnly: true, activity: activity, tracks: isMacTransfer) { request in
            lock.lock(); quietDuring[request.path] = activity.isQuiet; lock.unlock()
            return .text(200, "ok")
        }
        try server.start()
        defer { server.stop() }
        let port = try XCTUnwrap(waitForBoundPortSync(server))

        // The ping first: right after an upload, the quiet period would still be running.
        for (method, path) in [("GET", RD2Mac.Path.ping), ("POST", RD2Mac.Path.receive)] {
            var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(path)")!)
            request.httpMethod = method
            if method == "POST" { request.httpBody = Data(repeating: 7, count: 200_000) }
            let done = expectation(description: path)
            URLSession.shared.dataTask(with: request) { _, _, _ in done.fulfill() }.resume()
            wait(for: [done], timeout: 5)
        }

        lock.lock(); defer { lock.unlock() }
        XCTAssertEqual(quietDuring[RD2Mac.Path.receive], false)   // the upload is a transfer
        XCTAssertEqual(quietDuring[RD2Mac.Path.ping], true)       // a ping is not
        now = now.addingTimeInterval(TransferActivity.quietPeriod)
        let deadline = Date().addingTimeInterval(2)               // the reply's send completes async
        while !activity.isQuiet, Date() < deadline { usleep(20_000) }
        XCTAssertTrue(activity.isQuiet)
    }
}

final class SelfInstallTests: XCTestCase {

    func testSha256OfAFile() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("abc")
        try Data("abc".utf8).write(to: file)
        XCTAssertEqual(try FileDigest.sha256(of: file),
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testOnlyAnIntactBundleSignedLikeTheRequirementPasses() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let signed = try makeBundle(in: dir, name: "Signed", sign: true)
        let other = try makeBundle(in: dir, name: "Other", sign: true)
        let unsigned = try makeBundle(in: dir, name: "Unsigned", sign: false)
        let requirement = try XCTUnwrap(CodeIdentity.designatedRequirement(of: signed))

        XCTAssertTrue(CodeIdentity.isValid(signed, satisfying: requirement))
        XCTAssertFalse(CodeIdentity.isValid(other, satisfying: requirement))      // someone else's build
        XCTAssertFalse(CodeIdentity.isValid(unsigned, satisfying: requirement))

        // A byte changed after signing: the seal no longer matches.
        let exe = signed.appendingPathComponent("Contents/MacOS/Signed")
        let handle = try FileHandle(forWritingTo: exe)
        try handle.seekToEnd(); try handle.write(contentsOf: Data([0])); try handle.close()
        XCTAssertFalse(CodeIdentity.isValid(signed, satisfying: requirement))
    }

    func testSwapPutsTheNewBundleInPlaceAndKeepsTheOldAside() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let current = try makeMarkedDir(dir.appendingPathComponent("RD2Mac.app"), "old")
        let staged = try makeMarkedDir(dir.appendingPathComponent("staging/RD2Mac.app"), "new")

        let aside = try BundleSwap.replace(current, with: staged)

        XCTAssertEqual(try marker(current), "new")
        XCTAssertEqual(try marker(aside), "old")
        XCTAssertFalse(FileManager.default.fileExists(atPath: staged.path))
        BundleSwap.removeLeftovers(beside: current)
        XCTAssertFalse(FileManager.default.fileExists(atPath: aside.path))
        XCTAssertEqual(try marker(current), "new")
    }

    func testAFailedSwapPutsTheOldBundleBack() throws {
        let dir = try makeTempDir(); defer { try? FileManager.default.removeItem(at: dir) }
        let current = try makeMarkedDir(dir.appendingPathComponent("RD2Mac.app"), "old")
        let missing = dir.appendingPathComponent("staging/RD2Mac.app")

        XCTAssertThrowsError(try BundleSwap.replace(current, with: missing))

        XCTAssertEqual(try marker(current), "old")
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["RD2Mac.app"])
    }

    func testTheRelaunchedAppReadsWhichProcessToWaitFor() {
        XCTAssertEqual(RelaunchWait.pid(from: ["RD2Mac", RelaunchWait.argument, "4242"]), 4242)
        XCTAssertNil(RelaunchWait.pid(from: ["RD2Mac"]))
        XCTAssertNil(RelaunchWait.pid(from: ["RD2Mac", RelaunchWait.argument]))
        XCTAssertNil(RelaunchWait.pid(from: ["RD2Mac", RelaunchWait.argument, "x"]))
        XCTAssertNil(RelaunchWait.pid(from: ["RD2Mac", RelaunchWait.argument, "0"]))
    }

    func testTheRelaunchedAppStartsOnceTheOldOneIsGone() throws {
        let old = Process()
        old.executableURL = URL(fileURLWithPath: "/bin/sleep")
        old.arguments = ["0.3"]
        try old.run()
        let started = expectation(description: "start")
        var oldWasGone = false
        RelaunchWait.whenExited(old.processIdentifier, timeout: 5) {
            oldWasGone = !old.isRunning
            started.fulfill()
        }
        wait(for: [started], timeout: 3)    // well before the 5 s timeout
        XCTAssertTrue(oldWasGone)
    }

    func testTheRelaunchedAppStartsAnywayAfterTheTimeout() throws {
        let stuck = Process()
        stuck.executableURL = URL(fileURLWithPath: "/bin/sleep")
        stuck.arguments = ["10"]
        try stuck.run()
        defer { stuck.terminate() }
        let started = expectation(description: "start")
        RelaunchWait.whenExited(stuck.processIdentifier, timeout: 0.3) { started.fulfill() }
        wait(for: [started], timeout: 3)
        XCTAssertTrue(stuck.isRunning)       // it gave up waiting; the old one still runs
    }

    // MARK: fixtures

    /// A minimal app bundle (a copy of /usr/bin/true as its executable), ad-hoc signed or not.
    private func makeBundle(in dir: URL, name: String, sign: Bool) throws -> URL {
        let bundle = dir.appendingPathComponent("\(name).app")
        let macOS = bundle.appendingPathComponent("Contents/MacOS")
        try FileManager.default.createDirectory(at: macOS, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: URL(fileURLWithPath: "/usr/bin/true"),
                                         to: macOS.appendingPathComponent(name))
        let plist: [String: Any] = ["CFBundleIdentifier": "net.simioni.rd2mac.test.\(name)",
                                    "CFBundleExecutable": name, "CFBundlePackageType": "APPL"]
        try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
            .write(to: bundle.appendingPathComponent("Contents/Info.plist"))
        if sign {
            let codesign = Process()
            codesign.executableURL = URL(fileURLWithPath: "/usr/bin/codesign")
            codesign.arguments = ["--force", "--sign", "-", bundle.path]
            try codesign.run()
            codesign.waitUntilExit()
            XCTAssertEqual(codesign.terminationStatus, 0, "codesign failed")
        }
        return bundle
    }

    private func makeMarkedDir(_ url: URL, _ mark: String) throws -> URL {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        try Data(mark.utf8).write(to: url.appendingPathComponent("marker"))
        return url
    }

    private func marker(_ url: URL) throws -> String {
        String(decoding: try Data(contentsOf: url.appendingPathComponent("marker")), as: UTF8.self)
    }
}

// MARK: - Helpers

/// A loopback port nothing listens on: the kernel picks a free one for a socket that is
/// bound, never listened on, and closed — a connect to it is refused at once.
private func unusedLoopbackPort() -> UInt16 {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    defer { close(fd) }
    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_addr.s_addr = inet_addr("127.0.0.1")
    var length = socklen_t(MemoryLayout<sockaddr_in>.size)
    withUnsafeMutablePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            _ = bind(fd, $0, length)
            _ = getsockname(fd, $0, &length)
        }
    }
    return UInt16(bigEndian: addr.sin_port)
}

private func waitForBoundPortSync(_ server: HTTPServer, timeout: TimeInterval = 3) -> UInt16? {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if let port = server.boundPort, port != 0 { return port }
        usleep(20_000)
    }
    return nil
}

private func makeTempDir() throws -> URL {
    let dir = FileManager.default.temporaryDirectory
        .appendingPathComponent("rd2mac-tests-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
}
