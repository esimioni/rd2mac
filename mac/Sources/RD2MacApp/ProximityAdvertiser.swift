import CoreBluetooth
import RD2MacCore

/// Advertises the RD2Mac BLE beacon (service UUID only, no data) whenever the
/// app runs. The phone scans for it and gates automatic clipboard sync on the
/// received signal strength — the Mac never measures anything, both sync
/// directions are phone-driven. First run triggers the macOS Bluetooth privacy
/// prompt (System Settings → Privacy & Security → Bluetooth).
final class ProximityAdvertiser: NSObject, CBPeripheralManagerDelegate {

    private var manager: CBPeripheralManager?

    func start() {
        guard manager == nil else { return }
        manager = CBPeripheralManager(delegate: self, queue: nil)
    }

    func stop() {
        manager?.stopAdvertising()
        manager = nil
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        // Logged (Console: process RD2Mac) so a phone stuck on "Mac away" can be
        // triaged from this side: no "advertising" line here ⇒ the beacon never
        // went on air (BT off, permission denied, …).
        NSLog("RD2Mac: BLE peripheral state = %d", peripheral.state.rawValue)
        guard peripheral.state == .poweredOn else {
            // BT off / permission denied: proximity gating on the phone simply
            // sees "far"; the option there is off by default, so nothing breaks.
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [CBUUID(string: RD2Mac.bleServiceUUID)]
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            NSLog("RD2Mac: BLE beacon failed to advertise: %@", error.localizedDescription)
        } else {
            NSLog("RD2Mac: BLE beacon advertising")
        }
    }
}
