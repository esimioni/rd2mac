package net.simioni.rd2mac

import net.simioni.rd2mac.net.FilePayload
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

/** A payload over bytes already in memory — the small-file case of the tests. */
fun bytesPayload(name: String, mime: String, bytes: ByteArray) =
    FilePayload(name, mime, bytes.size.toLong()) { bytes.inputStream() }

/** A body this JVM could not hold even once: the whole heap plus 64 MiB. */
fun biggerThanTheHeap(): Long = Runtime.getRuntime().maxMemory() + 64L * 1024 * 1024

/** Deterministic bytes generated on the fly — a "file" of any size that is never
 *  materialized. Not periodic at block sizes, so an offset bug changes the digest. */
class PatternInputStream(private val length: Long) : InputStream() {
    private var pos = 0L

    private fun byteAt(i: Long): Int = ((i * 2654435761L) ushr 13).toInt() and 0xFF

    override fun read(): Int = if (pos >= length) -1 else byteAt(pos++)

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= length) return -1
        val n = minOf(len.toLong(), length - pos).toInt()
        for (k in 0 until n) b[off + k] = byteAt(pos + k).toByte()
        pos += n
        return n
    }
}

/** Hands out [inner]'s bytes in random short reads, like a socket or a provider pipe. */
class TrickleInputStream(private val inner: InputStream, private val rnd: Random) : InputStream() {
    override fun read(): Int = inner.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        if (len == 0) 0 else inner.read(b, off, 1 + rnd.nextInt(minOf(len, 97)))
}

/** Counts and hashes what is written to it, keeping nothing. */
class DigestSink : OutputStream() {
    private val md = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun write(b: Int) {
        md.update(b.toByte())
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        md.update(b, off, len)
        count += len
    }

    fun digest(): ByteArray = md.digest()
}

fun sha256Of(input: InputStream): ByteArray =
    DigestSink().also { sink -> input.use { it.copyTo(sink, 64 * 1024) } }.digest()
