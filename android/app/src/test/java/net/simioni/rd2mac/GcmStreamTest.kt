package net.simioni.rd2mac

import net.simioni.rd2mac.net.GcmStream
import net.simioni.rd2mac.net.RD2Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The streaming seal/open must put on the wire EXACTLY what the one-shot AES-GCM does —
 * the Mac opens it with CryptoKit and PROTOCOL.md did not change. The reference here is
 * the JVM's own AES/GCM (SunJCE): a GHASH written by other people.
 */
class GcmStreamTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val nonce = ByteArray(12) { (0xA0 + it).toByte() }
    // Around every block (16) and chunk (64 KiB) boundary, plus empty.
    private val sizes = listOf(0, 1, 15, 16, 17, 31, 32, 33, 1_000, 65_535, 65_536, 65_537, 200_003)

    private fun plain(n: Int) = ByteArray(n) { (it * 131 + 17).toByte() }

    private fun platformSeal(p: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(p)
    }

    private fun streamSeal(p: ByteArray, input: InputStream = p.inputStream()): ByteArray =
        ByteArrayOutputStream().also { GcmStream.seal(input, p.size.toLong(), it, key, nonce) }.toByteArray()

    private fun streamOpen(sealed: ByteArray, k: ByteArray = key): ByteArray =
        ByteArrayOutputStream().also { GcmStream.open(sealed.inputStream(), sealed.size.toLong(), it, k) }
            .toByteArray()

    private fun flipped(b: ByteArray, at: Int) = b.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    @Test fun sealIsByteIdenticalToPlatformGcm() {
        for (n in sizes) assertArrayEquals("size $n", platformSeal(plain(n)), streamSeal(plain(n)))
    }

    /** Reads come in arbitrary sizes off a socket or a provider: a partial GHASH block
     *  must carry across them. */
    @Test fun sealIsByteIdenticalWhateverTheReadSizes() {
        val rnd = Random(42)
        for (n in sizes) {
            val p = plain(n)
            assertArrayEquals("size $n", platformSeal(p), streamSeal(p, TrickleInputStream(p.inputStream(), rnd)))
        }
    }

    @Test fun opensWhatTheOneShotSealed() {
        for (n in sizes) assertArrayEquals("size $n", plain(n), streamOpen(RD2Crypto.seal(plain(n), key)))
    }

    @Test fun oneShotOpensWhatWeSealed() {
        for (n in sizes) {
            val sealed = ByteArrayOutputStream()
                .also { GcmStream.seal(plain(n).inputStream(), n.toLong(), it, key) }.toByteArray()
            assertArrayEquals("size $n", plain(n), RD2Crypto.open(sealed, key))
        }
    }

    @Test fun sealedLengthIsPlainPlusNonceAndTag() {
        for (n in sizes) assertEquals(GcmStream.sealedLength(n.toLong()), streamSeal(plain(n)).size.toLong())
        assertEquals(5L + 12 + 16, GcmStream.sealedLength(5))
    }

    @Test(expected = AEADBadTagException::class) fun tamperedCiphertextIsRejected() {
        streamOpen(flipped(streamSeal(plain(1_000)), 500))
    }

    @Test(expected = AEADBadTagException::class) fun tamperedTagIsRejected() {
        val sealed = streamSeal(plain(1_000))
        streamOpen(flipped(sealed, sealed.size - 1))
    }

    @Test(expected = AEADBadTagException::class) fun tamperedNonceIsRejected() {
        streamOpen(flipped(streamSeal(plain(1_000)), 0))
    }

    @Test(expected = AEADBadTagException::class) fun wrongKeyIsRejected() {
        streamOpen(streamSeal(plain(1_000)), ByteArray(32))
    }

    @Test(expected = AEADBadTagException::class) fun bodyShorterThanNonceAndTagIsRejected() {
        GcmStream.open(ByteArray(20).inputStream(), 20, ByteArrayOutputStream(), key)
    }

    /** A body cut short is a failed transfer — never a shorter file. */
    @Test(expected = EOFException::class) fun truncatedSealedBodyFails() {
        val sealed = streamSeal(plain(1_000))
        GcmStream.open(sealed.copyOf(700).inputStream(), sealed.size.toLong(), ByteArrayOutputStream(), key)
    }

    @Test(expected = EOFException::class) fun plaintextShorterThanDeclaredFails() {
        GcmStream.seal(plain(10).inputStream(), 11, ByteArrayOutputStream(), key, nonce)
    }

    /**
     * THE bug (29/09/2026): a 147 MB video shared from the gallery was read whole and
     * sealed in memory — ~3 copies, past the app heap → OutOfMemoryError → the app died.
     * A body bigger than this JVM's whole heap must seal and open end to end.
     */
    @Test(timeout = 120_000) fun sealsAndOpensABodyBiggerThanTheHeap() {
        val length = biggerThanTheHeap()
        val pipeIn = PipedInputStream(1 shl 20)
        val pipeOut = PipedOutputStream(pipeIn)
        var sealFailure: Throwable? = null
        val sealer = thread {
            try {
                pipeOut.use { GcmStream.seal(PatternInputStream(length), length, it, key) }
            } catch (t: Throwable) {
                sealFailure = t
            }
        }
        val plainOut = DigestSink()
        val opened = try {
            GcmStream.open(pipeIn, GcmStream.sealedLength(length), plainOut, key)
        } finally {
            pipeIn.close()
            sealer.join()
        }
        sealFailure?.let { throw it }
        assertEquals(length, opened)
        assertEquals(length, plainOut.count)
        assertArrayEquals(sha256Of(PatternInputStream(length)), plainOut.digest())
    }
}
