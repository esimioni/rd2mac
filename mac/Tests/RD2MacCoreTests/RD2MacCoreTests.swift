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
    private func multiScreenshotPhone(available: Int, key: Data) -> HTTPServer {
        HTTPServer(port: 0) { request in
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
        let phonePort = try XCTUnwrap(waitForBoundPortSync(phone))
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

// MARK: - Helpers

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
