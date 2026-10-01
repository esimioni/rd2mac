import Foundation

/// File transfers in flight, both directions. An update restarts the app, so it waits
/// until nothing has moved for `quietPeriod` — long enough to span the gaps between the
/// files of one batch, which arrive one request at a time.
public final class TransferActivity {
    public static let shared = TransferActivity()
    public static let quietPeriod: TimeInterval = 30

    private let lock = NSLock()
    private let now: () -> Date
    private var inFlight = 0
    private var lastEnd: Date?

    public init(now: @escaping () -> Date = Date.init) {
        self.now = now
    }

    public func begin() {
        lock.lock(); defer { lock.unlock() }
        inFlight += 1
    }

    public func end() {
        lock.lock(); defer { lock.unlock() }
        inFlight = max(0, inFlight - 1)
        lastEnd = now()
    }

    /// Runs `work` as one transfer.
    public func track<T>(_ work: () throws -> T) rethrows -> T {
        begin()
        defer { end() }
        return try work()
    }

    /// Nothing in flight, and the last transfer ended at least `quietPeriod` ago.
    public var isQuiet: Bool {
        lock.lock(); defer { lock.unlock() }
        guard inFlight == 0 else { return false }
        guard let lastEnd else { return true }
        return now().timeIntervalSince(lastEnd) >= Self.quietPeriod
    }
}
