package com.hometv.tv.ui.components

import com.hometv.tv.core.api.Avatar
import com.hometv.tv.data.ProfileAvatar

/** The signed-in profile's avatar on the right of the top nav (opens the Profile Picker). */
data class AvatarUi(val initial: String, val color: String?) {
    companion object {
        /** [ProfileAvatar.initial]. */
        fun initial(name: String): String = ProfileAvatar.initial(name)

        /** [ProfileAvatar.color]. */
        fun color(avatarId: String?, avatars: List<Avatar>?): String? = ProfileAvatar.color(avatarId, avatars)

        fun of(name: String, avatarId: String?, avatars: List<Avatar>?): AvatarUi = AvatarUi(initial(name), color(avatarId, avatars))
    }
}
