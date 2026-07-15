package net.simioni.rd2mac

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingTest {

    @Test fun imagesAndVideosGoToGallery() {
        assertEquals(SaveDestination.GALLERY, SaveRouting.destinationFor("image/jpeg"))
        assertEquals(SaveDestination.GALLERY, SaveRouting.destinationFor("image/png"))
        assertEquals(SaveDestination.GALLERY, SaveRouting.destinationFor("video/mp4"))
    }

    @Test fun everythingElseGoesToDownloads() {
        assertEquals(SaveDestination.DOWNLOADS, SaveRouting.destinationFor("application/pdf"))
        assertEquals(SaveDestination.DOWNLOADS, SaveRouting.destinationFor("text/plain"))
        assertEquals(SaveDestination.DOWNLOADS, SaveRouting.destinationFor(null))
    }

    @Test fun sanitizeStripsPathTraversal() {
        assertEquals("passwd", FileNaming.sanitize("../../etc/passwd"))
        assertEquals("c.png", FileNaming.sanitize("a/b/c.png"))
        assertEquals("b.png", FileNaming.sanitize("a\\b.png"))
    }

    @Test fun sanitizePreservesNormalNames() {
        assertEquals("Screenshot 2026-07-07.png", FileNaming.sanitize("Screenshot 2026-07-07.png"))
    }

    @Test fun sanitizeFallsBackOnDegenerate() {
        assertEquals("rd2mac-file", FileNaming.sanitize(".."))
        assertEquals("rd2mac-file", FileNaming.sanitize(""))
    }

    @Test fun platformFromWire() {
        assertEquals(Platform.MACOS, Platform.from("macos"))
        assertEquals(Platform.ANDROID, Platform.from("android"))
        assertEquals(Platform.UNKNOWN, Platform.from("windows"))
    }
}
