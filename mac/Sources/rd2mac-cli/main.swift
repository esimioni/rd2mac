import Foundation
import RD2MacCore

func usage() {
    print("""
    rd2mac — RD2Mac CLI

    Usage:
      rd2mac pull-screenshot [n]     Pull the phone's latest n screenshots (default 1)
                                     to ~/Downloads, printing each saved path (one per
                                     line, for an AI agent to read).
      rd2mac pull [image|video] [n]  Pull the phone's latest n gallery items (default 1).
      rd2mac send <file>...          Send local file(s) to the phone (gallery/Downloads).
      rd2mac peer                 Show the phone discovered on the network.
      rd2mac ping <host> <port>   Ping a peer's /ping endpoint (debug).
      rd2mac version              Print protocol version.

    Requires the RD2Mac menu-bar app to be running (it holds the network state).
    """)
}

/// Call the app's loopback control endpoint.
func control(_ method: String, _ path: String, timeout: TimeInterval = 60) async -> (status: Int, data: Data) {
    var request = URLRequest(url: URL(string: "http://127.0.0.1:\(RD2Mac.controlPort)\(path)")!)
    request.httpMethod = method
    request.timeoutInterval = timeout
    guard let (data, response) = try? await URLSession.shared.data(for: request) else {
        return (0, Data())
    }
    return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
}

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

/// Pull via control, print each saved path on success (stdout = paths only, one
/// per line — so an AI agent can read every pulled file).
func pull(_ controlPath: String, label: String) async {
    let (status, data) = await control("POST", controlPath)
    if status == 0 {
        fail("\(label) failed: control server not responding — is the RD2Mac app running?")
    }
    guard status == 200, let results = try? JSONDecoder().decode([ReceiveResult].self, from: data) else {
        fail("\(label) failed: \(String(data: data, encoding: .utf8) ?? "HTTP \(status)")")
    }
    for result in results { print(result.saved) }
}

let args = Array(CommandLine.arguments.dropFirst())
guard let command = args.first else { usage(); exit(2) }

switch command {
case "version", "-v", "--version":
    print("RD2Mac protocol v\(RD2Mac.protocolVersion)")

case "pull-screenshot":
    let n = PullArgs.count(Array(args.dropFirst()))
    await pull("/pull-screenshot?count=\(n)", label: "pull-screenshot")

case "pull":
    let (type, n) = PullArgs.parse(Array(args.dropFirst()))
    await pull("/pull-media?type=\(type)&count=\(n)", label: "pull")

case "send":
    guard args.count >= 2 else { usage(); exit(2) }
    var failures = 0
    for file in args.dropFirst() {
        let absolute = file.hasPrefix("/") ? file
            : FileManager.default.currentDirectoryPath + "/" + file
        // sendTarget owns the query encoding (Services menu uses the same one) —
        // .alphanumerics here was Unicode-wide and let "é" through raw.
        let (status, data) = await control("POST", ControlService.sendTarget(forPath: absolute))
        if status == 200, let result = try? JSONDecoder().decode(ReceiveResult.self, from: data) {
            print("sent \(file) → \(result.saved)")
        } else {
            FileHandle.standardError.write(Data(
                "send failed for \(file): \(String(data: data, encoding: .utf8) ?? "HTTP \(status)")\n".utf8))
            failures += 1
        }
    }
    if failures > 0 { exit(1) }

case "peer":
    let (status, data) = await control("GET", "/peer")
    if status == 200 {
        print(String(data: data, encoding: .utf8) ?? "")
    } else if status == 0 {
        fail("control server not responding — is the RD2Mac app running?")
    } else {
        fail(String(data: data, encoding: .utf8) ?? "HTTP \(status)")
    }

case "ping":
    guard args.count >= 3, let port = UInt16(args[2]) else { usage(); exit(2) }
    let client = HTTPClient(peerName: Host.current().localizedName ?? "Mac")
    if let identity = await client.ping(host: args[1], port: port) {
        print("pong: \(identity.name) [\(identity.platform.rawValue)] v\(identity.v)")
    } else {
        fail("no response from \(args[1]):\(port)")
    }

default:
    usage()
    exit(2)
}
