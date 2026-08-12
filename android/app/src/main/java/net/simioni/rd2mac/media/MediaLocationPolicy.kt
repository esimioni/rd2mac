package net.simioni.rd2mac.media

/**
 * Whether photos leave this phone with their GPS Exif tags.
 *
 * Two independent things have to hold, and they can drift apart: the user's switch
 * (persisted here) and ACCESS_MEDIA_LOCATION (revocable in system Settings, with no
 * notification to the app). A stale "on" switch would otherwise tell the user their
 * photos carry coordinates while MediaProvider quietly strips every one of them.
 */
object MediaLocationPolicy {

    fun effective(switchOn: Boolean, permissionGranted: Boolean): Boolean =
        switchOn && permissionGranted
}
