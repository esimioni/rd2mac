import Foundation

/// The CLI's way into the running app: one call to the loopback control server, one
/// classified outcome. Lives in Core (not the CLI target) so it can be unit-tested.
public struct ControlClient {
    /// How long a caller waits for the app to finish a pull or a send. The app answers
    /// only when the whole operation is over and bounds every phone transfer itself
    /// (`HTTPClient`'s idle timeout, the send's three attempts), so this only guards
    /// against a hung app. Never an idle-style 60 s: a pull longer than that was cut
    /// short and blamed on the app not running, while the app kept pulling (01/10/2026).
    /// The Services menu uses it; the Finder and Share extensions mirror it (they build
    /// without RD2MacCore).
    public static let workTimeout: TimeInterval = 24 * 60 * 60
    /// `/peer` is answered from memory, at once.
    public static let quickTimeout: TimeInterval = 60

    public enum Outcome: Equatable {
        /// The app answered (any HTTP status).
        case reply(status: Int, body: Data)
        /// Nothing listens on the control port: the app is not running.
        case notRunning
        /// The app did not answer within the caller's timeout — it may still be working.
        case timedOut
        /// Any other transport failure (e.g. the app quit mid-call).
        case failed(String)

        /// What the CLI prints when the call did not end in the reply it wanted.
        public var errorText: String {
            switch self {
            case let .reply(status, body):
                let text = String(data: body, encoding: .utf8) ?? ""
                return text.isEmpty ? "HTTP \(status)" : text
            case .notRunning:
                return "control server not responding — is the RD2Mac app running?"
            case .timedOut:
                return "the RD2Mac app did not answer in time — it may still be working"
            case let .failed(reason):
                return "lost the RD2Mac app mid-call: \(reason)"
            }
        }
    }

    public let port: UInt16

    public init(port: UInt16 = RD2Mac.controlPort) {
        self.port = port
    }

    public func call(_ method: String, _ path: String, timeout: TimeInterval) async -> Outcome {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(path)")!)
        request.httpMethod = method
        request.timeoutInterval = timeout
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            return .reply(status: (response as? HTTPURLResponse)?.statusCode ?? 0, body: data)
        } catch let error as URLError where error.code == .cannotConnectToHost {
            return .notRunning
        } catch let error as URLError where error.code == .timedOut {
            return .timedOut
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}
