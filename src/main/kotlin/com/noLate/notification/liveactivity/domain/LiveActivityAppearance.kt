package com.noLate.notification.liveactivity.domain

/** Device-scoped iOS appearance snapshot used to render remotely-started Live Activities. */
enum class LiveActivityAppearance(
    val wireValue: String,
) {
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromWireValue(value: String): LiveActivityAppearance? =
            entries.firstOrNull { it.wireValue == value.trim().lowercase() }
    }
}
