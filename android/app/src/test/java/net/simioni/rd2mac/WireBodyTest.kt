package net.simioni.rd2mac

import net.simioni.rd2mac.io.BoundedInputStream
import net.simioni.rd2mac.io.CountingInputStream
import net.simioni.rd2mac.io.Streams
import net.simioni.rd2mac.net.GcmStream
import net.simioni.rd2mac.net.RD2Crypto
import net.simioni.rd2mac.net.WireBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** The body rule of both directions (sealed when paired, passthrough when not) and the
 *  exact-length plumbing under it. */
class WireBodyTest {
    private val key = ByteArray(32) { 0x5C }
    private val msg = "a file body".toByteArray()

    private fun write(wire: WireBody) =
        ByteArrayOutputStream().also { wire.write(msg.inputStream(), msg.size.toLong(), it) }.toByteArray()

    @Test fun pairedBodyIsSealedAndOpensBack() {
        val wire = WireBody(key)
        val onWire = write(wire)
        assertEquals(wire.wireLength(msg.size.toLong()), onWire.size.toLong())
        assertArrayEquals(msg, RD2Crypto.open(onWire, key))      // what the Mac does with it
        val back = ByteArrayOutputStream()
        assertEquals(msg.size.toLong(), wire.read(onWire.inputStream(), onWire.size.toLong(), back))
        assertArrayEquals(msg, back.toByteArray())
    }

    /** Unpaired legacy path: nothing to seal with, the bytes travel as they are. */
    @Test fun unpairedBodyPassesThrough() {
        val wire = WireBody(null)
        val onWire = write(wire)
        assertArrayEquals(msg, onWire)
        assertEquals(msg.size.toLong(), wire.wireLength(msg.size.toLong()))
        val back = ByteArrayOutputStream()
        assertEquals(msg.size.toLong(), wire.read(onWire.inputStream(), onWire.size.toLong(), back))
        assertArrayEquals(msg, back.toByteArray())
    }

    @Test fun sealedWireLengthAddsNonceAndTag() {
        assertEquals(GcmStream.sealedLength(100), WireBody(key).wireLength(100))
    }

    @Test(expected = EOFException::class) fun unpairedBodyCutShortFails() {
        WireBody(null).read(msg.inputStream(), msg.size + 1L, ByteArrayOutputStream())
    }

    @Test fun copyExactlyStopsAtTheDeclaredLength() {
        val out = ByteArrayOutputStream()
        Streams.copyExactly("0123456789".byteInputStream(), 4, out)
        assertEquals("0123", out.toString())
    }

    /** The request body view never reads past Content-Length — the socket's next bytes
     *  belong to nobody. */
    @Test fun boundedStreamEndsAtItsLimit() {
        val source = "0123456789".byteInputStream()
        val bounded = BoundedInputStream(source, 4)
        assertEquals("0123", String(bounded.readBytes()))
        assertEquals(-1, bounded.read())
        assertEquals('4'.code, source.read())
    }

    @Test fun drainReportsWhetherTheBodyFitTheLimit() {
        assertTrue(Streams.drain(BoundedInputStream(ByteArray(100).inputStream(), 100), 100))
        assertFalse(Streams.drain(BoundedInputStream(ByteArray(101).inputStream(), 101), 100))
    }

    @Test fun countingStreamReportsTheRunningTotal() {
        val counts = mutableListOf<Long>()
        val input = CountingInputStream("0123456789".byteInputStream()) { counts += it }
        input.read()
        input.read(ByteArray(4))
        input.readBytes()
        assertEquals(listOf(1L, 5L, 10L), counts)
    }
}
