package com.hometv.tv.core.playback

import com.hometv.tv.core.api.MediaFile

/** How a version is named to the viewer: the same rule as the web app's `versionName` (`web/src/player/versions.ts`). */
object VersionNames {
    /**
     * The file name without its extension (a leading or trailing dot is not one); null when the name is blank, for the
     * app to show its "unknown" text.
     */
    fun name(file: MediaFile): String? {
        val name = file.file_name.trim()
        if (name.isEmpty()) return null
        val dot = name.lastIndexOf('.')
        return if (dot > 0 && dot < name.length - 1) name.substring(0, dot) else name
    }
}
