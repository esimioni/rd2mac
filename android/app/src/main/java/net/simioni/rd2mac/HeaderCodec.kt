package net.simioni.rd2mac

import net.simioni.rd2mac.net.RD2Crypto
import java.io.ByteArrayOutputStream

/**
 * Percent-encode/decode for filename HTTP headers. Byte-for-byte identical to the
 * Swift `String.rd2macHeaderEncoded` extension: everything except ASCII
 * alphanumerics becomes %XX of its UTF-8 bytes. Shared fixed vector lives in both
 * `HeaderCodecTest` (Kotlin) and `testHeaderEncodingFixedVector` (Swift).
 */
object HeaderCodec {
    fun encode(input: String): String {
        val sb = StringBuilder()
        for (byte in input.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    fun decode(input: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.write(hex)
                    i += 3
                    continue
                }
            }
            out.write(ch.code)
            i++
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }
}

/**
 * Filename headers on paired transfers travel encrypted — base64(seal(utf8)) with
 * the pairing key — so a sniffer who captured the auth token still learns nothing
 * from `X-RD2Mac-Filename`. Without a key (pre-pairing, loopback control) falls
 * back to plain percent-encoding. Mirror of the Swift `HeaderCrypt`.
 */
object HeaderCrypt {
    fun encode(value: String, key: ByteArray?): String {
        if (key == null) return HeaderCodec.encode(value)
        return java.util.Base64.getEncoder()
            .encodeToString(RD2Crypto.seal(value.toByteArray(Charsets.UTF_8), key))
    }

    /** Sealed value → plaintext; anything that doesn't open (plain-encoded peer,
     *  garbage) falls back to the percent decode — a filename never hard-fails. */
    fun decode(value: String, key: ByteArray?): String {
        if (key != null) {
            try {
                val blob = java.util.Base64.getDecoder().decode(value)
                return String(RD2Crypto.open(blob, key), Charsets.UTF_8)
            } catch (_: Exception) {
                // not sealed with our key — treat as plain
            }
        }
        return HeaderCodec.decode(value)
    }
}
