package net.simioni.rd2mac

import net.simioni.rd2mac.update.AppVersion
import net.simioni.rd2mac.update.FileDigest
import net.simioni.rd2mac.update.Release
import net.simioni.rd2mac.update.UpdateFeed
import net.simioni.rd2mac.update.UpdatePolicy
import net.simioni.rd2mac.update.UpdateSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Self-update: what the feed says, which version wins, when to look. Mirror of the
 *  Mac's UpdateFeedTests — both apps read the same release. */
class UpdateFeedTest {

    /** The shape GitHub returned for v0.5 (01/10/2026), trimmed to what the apps read. */
    private fun feed(
        tag: String = "v0.5",
        asset: String = "RD2Mac.apk",
        digest: String? = "sha256:9A493E80E61E4ED298DBAEAEB6E8DE4140A1DC39BBDAE413868D1DA2786FD6EF",
        prerelease: Boolean = false,
    ): String {
        val digestField = digest?.let { "\"digest\": \"$it\"," } ?: ""
        return """
            {"tag_name": "$tag", "name": "RD2Mac 0.5", "draft": false, "prerelease": $prerelease,
             "assets": [
              {"name": "RD2Mac.dmg", "size": 2232313,
               "digest": "sha256:677accf633b905bea7ae684632ef49fb3bf47acee8c4a0ef0e9fc87206f5b3cc",
               "browser_download_url": "https://github.com/esimioni/rd2mac/releases/download/v0.5/RD2Mac.dmg"},
              {"name": "$asset", "size": 11056702, $digestField
               "browser_download_url": "https://github.com/esimioni/rd2mac/releases/download/v0.5/$asset"}
             ]}
        """.trimIndent()
    }

    @Test fun readsTheAndroidAssetOfTheLatestRelease() {
        assertEquals(
            Release(
                version = "0.5",
                assetUrl = "https://github.com/esimioni/rd2mac/releases/download/v0.5/RD2Mac.apk",
                size = 11056702,
                sha256 = "9a493e80e61e4ed298dbaeaeb6e8de4140a1dc39bbdae413868d1da2786fd6ef",
            ),
            UpdateFeed.parse(feed(), UpdateFeed.ANDROID_ASSET),
        )
    }

    @Test fun noAssetNoPrereleaseNoGarbage() {
        assertNull(UpdateFeed.parse(feed(asset = "RD2Mac.aab"), UpdateFeed.ANDROID_ASSET))
        assertNull(UpdateFeed.parse(feed(prerelease = true), UpdateFeed.ANDROID_ASSET))
        assertNull(UpdateFeed.parse("""{"message": "Not Found"}""", UpdateFeed.ANDROID_ASSET))
        assertNull(UpdateFeed.parse("<html>", UpdateFeed.ANDROID_ASSET))
    }

    @Test fun onlyAWellFormedSha256DigestIsKept() {
        assertNull(UpdateFeed.parse(feed(digest = null), UpdateFeed.ANDROID_ASSET)?.sha256)
        assertNull(UpdateFeed.parse(feed(digest = "md5:0123"), UpdateFeed.ANDROID_ASSET)?.sha256)
        assertNull(UpdateFeed.parse(feed(digest = "sha256:xyz"), UpdateFeed.ANDROID_ASSET)?.sha256)
        assertEquals("0.7", UpdateFeed.parse(feed(tag = "0.7"), UpdateFeed.ANDROID_ASSET)?.version)
    }

    @Test fun onlyAHigherVersionIsNewer() {
        assertTrue(AppVersion.isNewer("0.6", "0.5"))
        assertTrue(AppVersion.isNewer("0.10", "0.9"))        // numeric, not text
        assertTrue(AppVersion.isNewer("1.0", "0.9.9"))
        assertFalse(AppVersion.isNewer("0.5", "0.5"))
        assertFalse(AppVersion.isNewer("0.6.0", "0.6"))
        assertFalse(AppVersion.isNewer("0.4", "0.5"))        // never a downgrade
    }

    @Test fun anUnreadableVersionNeverTriggersAnUpdate() {
        assertFalse(AppVersion.isNewer("0.6-beta", "0.5"))
        assertFalse(AppVersion.isNewer("", "0.5"))
        assertFalse(AppVersion.isNewer("1..0", "0.5"))
        assertFalse(AppVersion.isNewer("+1.0", "0.5"))
        assertFalse(AppVersion.isNewer("0.6", "dev"))
    }

    @Test fun aRejectedReleaseIsNotFetchedAgain() {
        assertTrue(UpdatePolicy.wants("0.6", "0.5", rejected = null))
        assertFalse(UpdatePolicy.wants("0.6", "0.5", rejected = "0.6"))    // already turned down
        assertTrue(UpdatePolicy.wants("0.7", "0.5", rejected = "0.6"))     // a new release is looked at
        assertFalse(UpdatePolicy.wants("0.5", "0.5", rejected = null))
    }

    @Test fun oneCheckADay() {
        val now = 1_800_000_000_000L
        assertTrue(UpdateSchedule.isDue(null, now))
        assertFalse(UpdateSchedule.isDue(now - (UpdateSchedule.INTERVAL_MS - 60_000), now))
        assertTrue(UpdateSchedule.isDue(now - UpdateSchedule.INTERVAL_MS, now))
        // A clock set back behind the last check must not silence updates for good.
        assertTrue(UpdateSchedule.isDue(now + 3_600_000, now))
    }

    @Test fun sha256OfAFile() {
        val file = File.createTempFile("abc", ".bin").apply { writeText("abc"); deleteOnExit() }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileDigest.sha256(file))
    }
}
