package net.simioni.rd2mac

import android.content.Context
import android.os.Build
import android.provider.Settings

/** User-facing device name (e.g. "Ana's Galaxy S23"), falling back to the model. */
fun deviceDisplayName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
