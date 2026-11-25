package net.simioni.rd2mac

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The share sheet lists RD2Mac only for the MIME types its manifest declares, and a type
 * left out fails SILENTLY: the entry is just not there. Two .m4a recordings shared from
 * Samsung's Voice Recorder (25/11/2026) found no RD2Mac — the filters held image/video/
 * application/text for one item and only image/video for several, while the README
 * promises "the photo or the file". These tests read the real manifest and resolve each
 * type the way Android does (AOSP `IntentFilter.findMimeType`, for a filter with types and
 * no scheme). The same contract on the device: `adb shell cmd package query-activities
 * -a <action> -t <type>` must list `net.simioni.rd2mac/.ShareReceiverActivity`.
 */
class ShareFilterTest {

    @Test fun oneVoiceRecorderM4aReachesRd2mac() {
        assertAccepted(ACTION_SEND, "audio/mp4")
    }

    @Test fun twoVoiceRecorderM4aReachRd2mac() {
        assertAccepted(ACTION_SEND_MULTIPLE, "audio/mp4")
        // What Voice Recorder really sends for a batch, even of .m4a only (logged 25/11/2026)
        assertAccepted(ACTION_SEND_MULTIPLE, "audio/*")
    }

    @Test fun anyFileReachesRd2macOneOrMany() {
        val types = listOf(
            "image/jpeg", "video/mp4", "audio/mpeg", "application/pdf", "application/zip",
            "application/msword", "application/octet-stream", "text/plain", "message/rfc822",
            "*/*", // a mixed selection: a photo and a PDF
        )
        for (action in listOf(ACTION_SEND, ACTION_SEND_MULTIPLE)) {
            for (type in types) assertAccepted(action, type)
        }
    }

    private fun assertAccepted(action: String, type: String) {
        val declared = declaredTypes(action)
        assertTrue(
            "$action $type: RD2Mac missing from the share sheet (the filter declares $declared)",
            accepts(declared, type),
        )
    }

    private fun accepts(declared: Set<String>, type: String): Boolean {
        if (type in declared) return true
        if (type == "*/*") return declared.isNotEmpty()
        if ("*/*" in declared) return true
        val family = type.substringBefore('/')
        if ("$family/*" in declared) return true
        return type == "$family/*" && declared.any { it.startsWith("$family/") }
    }

    /** The MIME types [ShareReceiverActivity] declares for [action], over all its filters. */
    private fun declaredTypes(action: String): Set<String> {
        val activity = elements(manifest(), "activity")
            .single { it.getAttribute("android:name") == ".ShareReceiverActivity" }
        return elements(activity, "intent-filter")
            .filter { filter -> elements(filter, "action").any { it.getAttribute("android:name") == action } }
            .flatMap { filter -> elements(filter, "data").map { it.getAttribute("android:mimeType") } }
            .toSet()
    }

    private fun manifest(): Element {
        val file = File("src/main/AndroidManifest.xml") // Gradle runs unit tests from the module dir
        assertTrue("manifest not found at ${file.absolutePath}", file.isFile)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun elements(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private companion object {
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    }
}