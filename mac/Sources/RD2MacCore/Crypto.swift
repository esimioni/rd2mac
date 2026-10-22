import Foundation
import CryptoKit

public enum RD2CryptoError: Error { case badPublicKey, badCiphertext }

/// End-to-end crypto for RD2Mac. At pairing, an ECDH (P-256) key agreement yields
/// a shared secret that never travels the wire; from it we derive the AES-256 key
/// (content), the auth token (header), and the 6-digit SAS the user compares to
/// rule out a man-in-the-middle. Content then travels AES-GCM sealed.
///
/// Every constant and format here MUST match the Android `RD2Crypto` byte-for-byte
/// — proven by a fixed-key vector shared by both test suites.
public enum RD2Crypto {
    /// Explicit HKDF salt (avoids the "empty salt" ambiguity across implementations).
    private static let salt = Data("RD2Mac/v1".utf8)
    private static let encInfo = Data("rd2mac-enc".utf8)
    private static let authInfo = Data("rd2mac-auth".utf8)
    private static let sasInfo = Data("rd2mac-sas".utf8)
    private static let beaconInfo = "rd2mac-beacon:"

    /// Everything derived from a completed key agreement.
    public struct Session: Equatable {
        public let keyBytes: Data    // 32 bytes, AES-256
        public let token: String     // 16-byte auth token, lowercase hex
        public let sas: String       // 6-digit code
        public var key: SymmetricKey { SymmetricKey(data: keyBytes) }
    }

    /// A fresh ephemeral P-256 key pair for one pairing.
    public static func generateKeyPair() -> P256.KeyAgreement.PrivateKey {
        P256.KeyAgreement.PrivateKey()
    }

    /// Public key on the wire: X9.63 uncompressed (0x04‖X‖Y, 65 bytes), base64.
    public static func publicKeyBase64(_ priv: P256.KeyAgreement.PrivateKey) -> String {
        priv.publicKey.x963Representation.base64EncodedString()
    }

    /// Derive the session from our private key and the peer's public key (base64 X9.63).
    public static func agree(privateKey: P256.KeyAgreement.PrivateKey,
                             peerPublicKeyBase64: String) throws -> Session {
        guard let pubData = Data(base64Encoded: peerPublicKeyBase64) else {
            throw RD2CryptoError.badPublicKey
        }
        let peerKey = try P256.KeyAgreement.PublicKey(x963Representation: pubData)
        let shared = try privateKey.sharedSecretFromKeyAgreement(with: peerKey)

        func derive(_ info: Data, _ n: Int) -> Data {
            shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt,
                                           sharedInfo: info, outputByteCount: n)
                .withUnsafeBytes { Data($0) }
        }
        let keyBytes = derive(encInfo, 32)
        let token = derive(authInfo, 16).map { String(format: "%02x", $0) }.joined()
        let sasBytes = derive(sasInfo, 4)
        let n = sasBytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } % 1_000_000
        return Session(keyBytes: keyBytes, token: token, sas: String(format: "%06u", n))
    }

    /// The paired Mac's BLE beacon for UTC `day`: a 128-bit service UUID only this
    /// pairing can compute (HKDF of the session key, which never travels), so the
    /// phone tells its Mac from any other RD2Mac Mac, and a passer-by can't follow
    /// the beacon from one day to the next.
    public static func beaconUUID(keyBytes: Data, day: Int64) -> String {
        let bytes = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: keyBytes), salt: salt,
                                           info: Data("\(beaconInfo)\(day)".utf8), outputByteCount: 16)
            .withUnsafeBytes { $0.load(as: uuid_t.self) }
        return UUID(uuid: bytes).uuidString.lowercased()
    }

    /// AES-GCM seal → [nonce(12)‖ciphertext‖tag(16)].
    public static func seal(_ plaintext: Data, keyBytes: Data) throws -> Data {
        let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: keyBytes))
        guard let combined = sealed.combined else { throw RD2CryptoError.badCiphertext }
        return combined
    }

    /// Open a [nonce‖ciphertext‖tag] blob. Throws on tampering / wrong key.
    public static func open(_ blob: Data, keyBytes: Data) throws -> Data {
        let box = try AES.GCM.SealedBox(combined: blob)
        return try AES.GCM.open(box, using: SymmetricKey(data: keyBytes))
    }
}
