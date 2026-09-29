package net.simioni.rd2mac.io

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Exact-length stream plumbing. Every body in the protocol has a declared length, and a
 * stream that ends early is a failed transfer — never a shorter file.
 */
object Streams {
    const val CHUNK = 64 * 1024

    /** Reads exactly [length] bytes of [input], handing them to [block] a chunk at a time
     *  (one reused buffer, so memory stays flat whatever the length).
     *  @throws EOFException when [input] ends first. */
    inline fun forEachChunk(input: InputStream, length: Long, block: (buf: ByteArray, n: Int) -> Unit) {
        val buf = ByteArray(CHUNK)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(CHUNK.toLong(), left).toInt())
            if (n < 0) throw EOFException("stream ended $left bytes short of $length")
            block(buf, n)
            left -= n
        }
    }

    fun copyExactly(input: InputStream, length: Long, out: OutputStream) =
        forEachChunk(input, length) { buf, n -> out.write(buf, 0, n) }

    fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            val r = input.read(out, at, n - at)
            if (r < 0) throw EOFException("stream ended ${n - at} bytes short of $n")
            at += r
        }
        return out
    }

    /** Reads and drops up to [limit] bytes. @return true when [input] ended within them. */
    fun drain(input: InputStream, limit: Long): Boolean {
        val buf = ByteArray(CHUNK)
        var left = limit
        while (true) {
            val n = input.read(buf, 0, minOf(CHUNK.toLong(), left + 1).toInt())
            if (n < 0) return true
            left -= n
            if (left < 0) return false
        }
    }
}

/** At most [left] bytes of [input] — never reads past them, so whatever follows on the
 *  socket stays untouched. Closing it does not close [input]. */
class BoundedInputStream(private val input: InputStream, private var left: Long) : InputStream() {

    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), left).toInt()
}

/** Reports the running total of bytes read through it — a transfer's progress, counted
 *  on the file's own bytes whatever the wire adds. */
class CountingInputStream(private val input: InputStream, private val onCount: (Long) -> Unit) : InputStream() {
    private var count = 0L

    override fun read(): Int = input.read().also { if (it >= 0) onCount(++count) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        input.read(b, off, len).also { if (it > 0) { count += it; onCount(count) } }

    override fun available(): Int = input.available()
    override fun close() = input.close()
}
