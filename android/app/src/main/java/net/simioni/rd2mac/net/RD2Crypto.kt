package net.simioni.rd2mac.net

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirror of the Swift `RD2Crypto`: ECDH P-256 → HKDF-SHA256 → AES-256-GCM, plus a
 * 6-digit SAS. Constants and formats match byte-for-byte (proven by `RD2CryptoTest`
 * against the same fixed-key vector emitted by the Swift `CryptoTests`).
 */
object RD2Crypto {
    private val SALT = "RD2Mac/v1".toByteArray()
    private val ENC_INFO = "rd2mac-enc".toByteArray()
    private val AUTH_INFO = "rd2mac-auth".toByteArray()
    private val SAS_INFO = "rd2mac-sas".toByteArray()

    class Session(val keyBytes: ByteArray, val token: String, val sas: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Session) return false
            return keyBytes.contentEquals(other.keyBytes) && token == other.token && sas == other.sas
        }
        override fun hashCode() = keyBytes.contentHashCode() * 31 + token.hashCode()
    }

    private val ecParams: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    /** Public key as X9.63 uncompressed (0x04‖X‖Y, 65 bytes), base64. */
    fun publicKeyBase64(pub: PublicKey): String {
        val w = (pub as ECPublicKey).w
        val out = ByteArray(65)
        out[0] = 0x04
        System.arraycopy(to32(w.affineX), 0, out, 1, 32)
        System.arraycopy(to32(w.affineY), 0, out, 33, 32)
        return Base64.getEncoder().encodeToString(out)
    }

    private fun parsePublicKey(b64: String): PublicKey {
        val raw = Base64.getDecoder().decode(b64)
        require(raw.size == 65 && raw[0].toInt() == 0x04) { "bad public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams))
    }

    fun agree(privateKey: PrivateKey, peerPublicKeyBase64: String): Session {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(parsePublicKey(peerPublicKeyBase64), true)
        val shared = ka.generateSecret()   // 32-byte X coordinate
        val keyBytes = hkdf(shared, ENC_INFO, 32)
        val token = hkdf(shared, AUTH_INFO, 16).joinToString("") { "%02x".format(it) }
        var n = 0L
        for (bb in hkdf(shared, SAS_INFO, 4)) n = (n shl 8) or (bb.toLong() and 0xff)
        return Session(keyBytes, token, "%06d".format(n % 1_000_000L))
    }

    /** Wire format of a sealed body: nonce ‖ ciphertext ‖ tag. */
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    fun newNonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }

    /** One-shot, whole message in memory — for small bodies (headers, clipboard, the
     *  sealed /receive reply). File bodies stream through [GcmStream]: same wire bytes. */
    fun seal(plaintext: ByteArray, keyBytes: ByteArray): ByteArray {
        val nonce = newNonce()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(blob: ByteArray, keyBytes: ByteArray): ByteArray {
        require(blob.size >= NONCE_BYTES + TAG_BYTES) { "bad ciphertext" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(TAG_BYTES * 8, blob.copyOfRange(0, NONCE_BYTES)))
        return cipher.doFinal(blob.copyOfRange(NONCE_BYTES, blob.size))
    }

    /** RFC 5869 HKDF-SHA256 (extract + expand). */
    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(SALT, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun to32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0].toInt() == 0 -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> b.copyOfRange(b.size - 32, b.size)
        }
    }

    /** Decode a base64 32-byte AES key; null if absent/invalid. */
    fun keyBytes(base64: String?): ByteArray? {
        if (base64.isNullOrEmpty()) return null
        return try {
            Base64.getDecoder().decode(base64).takeIf { it.size == 32 }
        } catch (e: Exception) {
            null
        }
    }

    /** Build a private key from a fixed 32-byte scalar (tests / determinism). */
    fun privateKeyFromRaw(raw: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), ecParams))
}
