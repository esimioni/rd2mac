package net.simioni.rd2mac

import net.simioni.rd2mac.media.PendingShares
import net.simioni.rd2mac.net.FilePayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A failed share's files, kept for "Try again". The case (27/11/2026): a screenshot
 * shared to RD2Mac while the phone couldn't find the Mac failed — and One UI's
 * "Delete shared screenshots" had already deleted it, so the failure lost it for good.
 */
class PendingSharesTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L
    private fun store() = PendingShares(tmp.root, now = { clock })
    private fun bytes(n: Int) = ByteArray(n) { (it * 7).toByte() }

    @Test fun keepsTheFilesForTheRetry() {
        val s = store()
        val id = s.keep(listOf(bytesPayload("shot.jpg", "image/jpeg", bytes(10)),
                               bytesPayload("doc.pdf", "application/pdf", bytes(20))))!!
        val kept = s.load(id)!!
        assertEquals(listOf("shot.jpg", "doc.pdf"), kept.map { it.name })
        assertEquals(listOf("image/jpeg", "application/pdf"), kept.map { it.mime })
        // What the retry sends: the very bytes that failed.
        val payload = kept[1].payload()
        assertEquals(20L, payload.length)
        assertArrayEquals(bytes(20), payload.open().readBytes())
    }

    @Test fun afterARetryOnlyWhatFailedAgainStays() {
        val s = store()
        val id = s.keep(listOf(bytesPayload("a", "image/jpeg", bytes(1)),
                               bytesPayload("b", "image/jpeg", bytes(2)),
                               bytesPayload("c", "image/jpeg", bytes(3))))!!
        s.settle(id, failedIndexes = listOf(1))
        assertEquals(listOf("b"), s.load(id)!!.map { it.name })
        // Indexes are positions in the CURRENT batch: the next retry settles against it.
        s.settle(id, failedIndexes = listOf(0))
        assertEquals(listOf("b"), s.load(id)!!.map { it.name })
    }

    @Test fun aBatchThatArrivedIsGone() {
        val s = store()
        val id = s.keep(listOf(bytesPayload("a", "image/jpeg", bytes(1))))!!
        s.settle(id, failedIndexes = emptyList())
        assertNull(s.load(id))
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun dismissingTheNoticeDiscardsTheFiles() {
        val s = store()
        val id = s.keep(listOf(bytesPayload("a", "image/jpeg", bytes(1))))!!
        s.discard(id)
        assertNull(s.load(id))
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun sweepDropsOnlyBatchesPastTheirAge() {
        val s = store()
        val old = s.keep(listOf(bytesPayload("old", "image/jpeg", bytes(1))))!!
        clock += 8 * 24 * 3600_000L
        val fresh = s.keep(listOf(bytesPayload("fresh", "image/jpeg", bytes(1))))!!
        s.sweep(maxAgeMs = 7 * 24 * 3600_000L)
        assertNull(s.load(old))
        assertNotNull(s.load(fresh))
    }

    @Test fun twoFailuresInTheSameMillisecondKeepBothBatches() {
        val s = store()
        val a = s.keep(listOf(bytesPayload("a", "image/jpeg", bytes(1))))!!
        val b = s.keep(listOf(bytesPayload("b", "image/jpeg", bytes(1))))!!
        assertTrue(a != b)
        assertEquals("a", s.load(a)!!.single().name)
        assertEquals("b", s.load(b)!!.single().name)
    }

    @Test fun anUnreadableFileIsLeftOutAndNothingKeptLeavesNoTrace() {
        val s = store()
        val broken = FilePayload("gone.jpg", "image/jpeg", 5) { throw IOException("grant gone") }
        assertNull(s.keep(listOf(broken)))
        assertNull(s.keep(emptyList()))
        assertEquals(0, tmp.root.listFiles()!!.size)
        val id = s.keep(listOf(broken, bytesPayload("ok.jpg", "image/jpeg", bytes(4))))!!
        assertEquals(listOf("ok.jpg"), s.load(id)!!.map { it.name })
    }

    @Test fun aNameFromAnotherAppNeverBecomesAPath() {
        val s = store()
        val id = s.keep(listOf(bytesPayload("../../evil.jpg", "image/jpeg", bytes(3))))!!
        val kept = s.load(id)!!.single()
        assertEquals("../../evil.jpg", kept.name)
        assertTrue(kept.file.canonicalPath.startsWith(tmp.root.canonicalPath + File.separator))
        assertFalse(File(tmp.root.parentFile, "evil.jpg").exists())
    }

    @Test fun anUnknownBatchLoadsAsNothing() {
        assertNull(store().load("no-such-batch"))
        assertNull(store().load("../escape"))
    }
}