import Foundation

/// Which beacon the Mac advertises: its pairing's (`RD2Crypto.beaconUUID`) for the
/// current UTC day — the phone listens for yesterday's, today's and tomorrow's.
/// Every Mac used to advertise one shared UUID, so a second RD2Mac Mac in the
/// house counted as "my Mac" (2026-10-22). Pure; mirror of the Kotlin `ProximityBeacon`.
public enum ProximityBeacon {
    public static func day(for date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    public static func uuid(keyBytes: Data, at date: Date) -> String {
        RD2Crypto.beaconUUID(keyBytes: keyBytes, day: day(for: date))
    }
}