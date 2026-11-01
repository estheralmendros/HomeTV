package com.hometv.tv.data

import com.hometv.tv.core.api.Avatar

/** The one avatar initial + colour rule (top nav, profile picker, ratings, Actividad); `AvatarUi` delegates to it. */
object ProfileAvatar {
    /** The profile's first letter, upper-cased ("" for a blank name). */
    fun initial(name: String): String = name.trim().take(1).uppercase()

    /** `#RRGGBB` of [avatarId] in the `/api/info` avatars, null when unknown. */
    fun color(avatarId: String?, avatars: List<Avatar>?): String? = avatars?.firstOrNull { it.id == avatarId }?.color
}
