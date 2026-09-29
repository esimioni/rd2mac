package net.simioni.rd2mac.net

import net.simioni.rd2mac.io.Streams
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming twin of [RD2Crypto.seal]/[RD2Crypto.open]: the SAME wire bytes — one
 * AES-256-GCM message, nonce ‖ ciphertext ‖ tag, so PROTOCOL.md and the Mac (CryptoKit)
 * are untouched — produced and consumed a chunk at a time, so a file never has to fit in
 * the app heap. A 147 MB video shared from the gallery was read whole and sealed in
 * memory (~3 copies) → OutOfMemoryError → the app died (29/09/2026).
 *
 * Why not `Cipher("AES/GCM/NoPadding").update()`: Android's provider (Conscrypt) appends
 * every update to an internal buffer and seals only in doFinal, so streaming through it
 * still holds the whole file (OpenSSLAeadCipher.updateInternal, source read 29/09/2026).
 * GCM with a 96-bit nonce is AES-CTR from inc32(J0) plus GHASH over the ciphertext,
 * tag = GHASH ⊕ E(K, J0) (NIST SP 800-38D §7): the platform's AES-CTR streams (hardware
 * AES), GHASH is [Ghash]. Byte identity with the platform's one-shot GCM: GcmStreamTest.
 */
object GcmStream {

    fun sealedLength(plainLength: Long): Long = plainLength + RD2Crypto.NONCE_BYTES + RD2Crypto.TAG_BYTES

    /** Reads exactly [plainLength] bytes of [plain] and writes nonce ‖ ciphertext ‖ tag. */
    fun seal(plain: InputStream, plainLength: Long, sealed: OutputStream, key: ByteArray,
             nonce: ByteArray = RD2Crypto.newNonce()) {
        val gcm = Gcm(key, nonce)
        val ct = ByteArray(Streams.CHUNK)
        sealed.write(nonce)
        Streams.forEachChunk(plain, plainLength) { buf, n ->
            gcm.ctr(buf, n, ct)
            gcm.ghash.update(ct, 0, n)
            sealed.write(ct, 0, n)
        }
        sealed.write(gcm.tag(plainLength))
    }

    /**
     * Reads exactly [sealedLength] bytes of [sealed] and writes the plaintext to [plain]
     * AS IT GOES — it is unauthenticated until this returns: on any exception, whatever
     * reached [plain] must be discarded. @return the plaintext length.
     */
    fun open(sealed: InputStream, sealedLength: Long, plain: OutputStream, key: ByteArray): Long {
        val ctLength = sealedLength - RD2Crypto.NONCE_BYTES - RD2Crypto.TAG_BYTES
        if (ctLength < 0) throw AEADBadTagException("sealed body shorter than nonce + tag")
        val gcm = Gcm(key, Streams.readExactly(sealed, RD2Crypto.NONCE_BYTES))
        val pt = ByteArray(Streams.CHUNK)
        Streams.forEachChunk(sealed, ctLength) { buf, n ->
            gcm.ghash.update(buf, 0, n)
            gcm.ctr(buf, n, pt)
            plain.write(pt, 0, n)
        }
        val tag = Streams.readExactly(sealed, RD2Crypto.TAG_BYTES)
        if (!MessageDigest.isEqual(gcm.tag(ctLength), tag)) throw AEADBadTagException("tag mismatch")
        return ctLength
    }

    /** One message's state: H and E(K, J0) from AES-ECB, the keystream from AES-CTR. */
    private class Gcm(key: ByteArray, nonce: ByteArray) {
        private val ctr: Cipher
        private val ej0: ByteArray
        val ghash: Ghash

        init {
            require(nonce.size == RD2Crypto.NONCE_BYTES) { "GCM here takes a 96-bit nonce" }
            val spec = SecretKeySpec(key, "AES")
            val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, spec) }
            ghash = Ghash(ecb.doFinal(ByteArray(16)))
            ej0 = ecb.doFinal(nonce + byteArrayOf(0, 0, 0, 1))                 // J0
            ctr = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, spec, IvParameterSpec(nonce + byteArrayOf(0, 0, 0, 2)))  // inc32(J0)
            }
        }

        /** CTR is the same operation both ways. The provider's 128-bit counter matches
         *  GCM's inc32 until 2^32 blocks — far past the 4 GiB body cap. */
        fun ctr(input: ByteArray, n: Int, output: ByteArray) {
            val written = ctr.update(input, 0, n, output, 0)
            check(written == n) { "AES-CTR buffered: $n in, $written out" }
        }

        fun tag(ctLength: Long): ByteArray {
            val s = ghash.finish(ctLength)
            for (i in s.indices) s[i] = (s[i].toInt() xor ej0[i].toInt()).toByte()
            return s
        }
    }
}

/**
 * GHASH_H (NIST SP 800-38D §6.4) over a byte stream, no AAD. Sixteen per-byte tables of
 * x^i·H (64 KiB per message) turn each 128-bit field multiply into 16 lookups. GCM's bit
 * order: bit 0 of a block is the MSB of byte 0, the coefficient of x^0 — so ·x is a right
 * shift, reduced by R = 0xE1‖0^120 when x^127 falls off.
 */
internal class Ghash(h: ByteArray) {
    private val tHi = LongArray(16 * 256)
    private val tLo = LongArray(16 * 256)
    private var yHi = 0L
    private var yLo = 0L
    private val partial = ByteArray(16)
    private var partialLen = 0

    init {
        require(h.size == 16)
        var vHi = h.beLong(0)
        var vLo = h.beLong(8)
        for (i in 0 until 128) {                           // table slot of bit i = H·x^i
            val slot = (i ushr 3) * 256 + (0x80 ushr (i and 7))
            tHi[slot] = vHi
            tLo[slot] = vLo
            val fallsOff = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (fallsOff != 0L) vHi = vHi xor R
        }
        for (base in 0 until 16 * 256 step 256) {          // the rest by linearity
            for (b in 1 until 256) {
                val low = b and -b
                if (low == b) continue
                tHi[base + b] = tHi[base + low] xor tHi[base + (b xor low)]
                tLo[base + b] = tLo[base + low] xor tLo[base + (b xor low)]
            }
        }
    }

    fun update(data: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        if (partialLen > 0) {
            val take = minOf(16 - partialLen, len)
            System.arraycopy(data, i, partial, partialLen, take)
            partialLen += take
            i += take
            if (partialLen < 16) return
            block(partial.beLong(0), partial.beLong(8))
            partialLen = 0
        }
        while (end - i >= 16) {
            block(data.beLong(i), data.beLong(i + 8))
            i += 16
        }
        if (i < end) {
            System.arraycopy(data, i, partial, 0, end - i)
            partialLen = end - i
        }
    }

    /** Zero-pads the last partial block, folds in the length block and returns GHASH. */
    fun finish(ctLength: Long): ByteArray {
        if (partialLen > 0) {
            partial.fill(0, partialLen, 16)
            block(partial.beLong(0), partial.beLong(8))
            partialLen = 0
        }
        block(0L, ctLength * 8)                            // [len(A)=0]64 ‖ [len(C) in bits]64
        return ByteArray(16).also { it.putBeLong(0, yHi); it.putBeLong(8, yLo) }
    }

    /** Y = (Y ⊕ X)·H */
    private fun block(xHi: Long, xLo: Long) {
        val aHi = yHi xor xHi
        val aLo = yLo xor xLo
        var zHi = 0L
        var zLo = 0L
        var base = 0
        for (shift in 56 downTo 0 step 8) {
            val b = ((aHi ushr shift) and 0xFF).toInt()
            zHi = zHi xor tHi[base + b]
            zLo = zLo xor tLo[base + b]
            base += 256
        }
        for (shift in 56 downTo 0 step 8) {
            val b = ((aLo ushr shift) and 0xFF).toInt()
            zHi = zHi xor tHi[base + b]
            zLo = zLo xor tLo[base + b]
            base += 256
        }
        yHi = zHi
        yLo = zLo
    }

    private companion object {
        const val R = 0xE1L shl 56

        fun ByteArray.beLong(at: Int): Long {
            var v = 0L
            for (k in 0 until 8) v = (v shl 8) or (this[at + k].toLong() and 0xFF)
            return v
        }

        fun ByteArray.putBeLong(at: Int, v: Long) {
            for (k in 0 until 8) this[at + k] = (v ushr (56 - 8 * k)).toByte()
        }
    }
}

/**
 * A transfer body as it travels: sealed with the pairing key when there is one,
 * passthrough on the unpaired legacy path — one rule for both directions, streamed.
 */
class WireBody(private val key: ByteArray?) {

    fun wireLength(plainLength: Long): Long =
        if (key != null) GcmStream.sealedLength(plainLength) else plainLength

    /** Puts exactly [plainLength] bytes of [plain] on [wire]. */
    fun write(plain: InputStream, plainLength: Long, wire: OutputStream) {
        if (key != null) GcmStream.seal(plain, plainLength, wire, key)
        else Streams.copyExactly(plain, plainLength, wire)
    }

    /** Takes exactly [wireLength] bytes off [wire] into [plain]; @return the plaintext
     *  length. Sealed bytes reach [plain] before the tag is checked — see [GcmStream.open]. */
    fun read(wire: InputStream, wireLength: Long, plain: OutputStream): Long =
        if (key != null) GcmStream.open(wire, wireLength, plain, key)
        else wireLength.also { Streams.copyExactly(wire, it, plain) }
}
