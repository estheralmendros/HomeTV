package com.hometv.tv.ui.components

import android.content.Context
import android.content.Intent

/** Tailscale's TV launch intent, or null when the app is not installed. */
fun tailscaleIntent(context: Context): Intent? {
    val pm = context.packageManager
    return pm.getLeanbackLaunchIntentForPackage(TAILSCALE_PACKAGE) ?: pm.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
}

const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
