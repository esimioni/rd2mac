import CoreBluetooth
import RD2MacCore

/// Advertises the RD2Mac BLE beacon (one service UUID, no data) while the app runs
/// and a phone is paired: the pairing's own UUID for the day (`ProximityBeacon`), so
/// the phone hears this Mac and not another RD2Mac Mac nearby. The phone scans for
/// it and gates automatic clipboard sync on the received signal strength — the Mac
/// never measures anything, both sync directions are phone-driven. First run
/// triggers the macOS Bluetooth privacy prompt (System Settings → Privacy & Security
/// → Bluetooth).
final class ProximityAdvertiser: NSObject, CBPeripheralManagerDelegate {

    /// The pairing's session key; nil = unpaired, nothing to advertise.
    private let keyProvider: () -> Data?
    private var manager: CBPeripheralManager?
    /// The UUID on the air (nil = none).
    private var advertised: String?
    /// The UUID turns over at 00:00 UTC; a pairing change calls `refresh()` itself.
    private var rotation: Timer?

    init(keyProvider: @escaping () -> Data?) {
        self.keyProvider = keyProvider
    }

    func start() {
        guard manager == nil else { return }
        manager = CBPeripheralManager(delegate: self, queue: nil)
        rotation = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
            self?.refresh()
        }
    }

    func stop() {
        rotation?.invalidate()
        rotation = nil
        manager?.stopAdvertising()
        manager = nil
        advertised = nil
    }

    /// Put on the air the beacon the pairing calls for right now (or none).
    func refresh() {
        guard let manager, manager.state == .poweredOn else { return }
        let wanted = keyProvider().map { ProximityBeacon.uuid(keyBytes: $0, at: Date()) }
        guard wanted != advertised else { return }
        manager.stopAdvertising()
        advertised = wanted
        guard let wanted else {
            NSLog("RD2Mac: BLE beacon off (no pairing)")
            return
        }
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: wanted)]])
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        // Logged (Console: process RD2Mac) so a phone stuck on "Mac away" can be
        // triaged from this side: no "advertising" line here ⇒ the beacon never
        // went on air (BT off, permission denied, no pairing, …).
        NSLog("RD2Mac: BLE peripheral state = %d", peripheral.state.rawValue)
        guard peripheral.state == .poweredOn else {
            // BT off / permission denied: proximity gating on the phone simply
            // sees "far"; the option there is off by default, so nothing breaks.
            advertised = nil
            return
        }
        refresh()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            NSLog("RD2Mac: BLE beacon failed to advertise: %@", error.localizedDescription)
        } else {
            NSLog("RD2Mac: BLE beacon advertising")
        }
    }
}
