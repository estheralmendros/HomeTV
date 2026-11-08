package com.hometv.tv.playback

/** Whether libVLC can run on this device; false keeps every file on ExoPlayer (prd.md decision 11). */
fun interface VlcAvailability {
    fun isAvailable(): Boolean
}

/** Thrown by [LibVlcProvider.get] when LibVLC can't be created (the cause is logged once). */
class VlcUnavailableException(cause: Throwable) : RuntimeException(cause)

/**
 * Process-wide LibVLC. **Availability and creation are separate**:
 * - [availability] is cheap and never creates LibVLC or loads native code: true when [abis] contains
 *   armeabi-v7a or arm64-v8a **and** no earlier [get] failed in this process. Safe on the main thread at any time.
 * - [get] creates the instance lazily, on the main thread, the first time a VLC controller opens a media. One
 *   instance then serves every Player screen. A `Throwable` from [factory] is logged once, recorded (so
 *   [availability] turns false for the rest of the process) and rethrown as [VlcUnavailableException]; every
 *   later [get] throws at once without calling [factory] again.
 *
 * Generic over the instance type so the JVM test needs no libVLC class (production: `LibVlcProvider<LibVLC>`).
 */
class LibVlcProvider<T : Any>(
    private val abis: List<String>,
    private val factory: () -> T,
    private val log: (String, Throwable?) -> Unit,
) {
    private var instance: T? = null
    private var failure: Throwable? = null

    val availability: VlcAvailability = VlcAvailability { failure == null && abis.any { it in ARM_ABIS } }

    fun get(): T {
        instance?.let { return it }
        failure?.let { throw VlcUnavailableException(it) }
        return try {
            factory().also { instance = it }
        } catch (e: Throwable) {
            failure = e
            log("LibVLC unavailable", e)
            throw VlcUnavailableException(e)
        }
    }

    private companion object {
        /** The ABIs the APK ships libVLC for (`ndk.abiFilters`). */
        val ARM_ABIS = setOf("armeabi-v7a", "arm64-v8a")
    }
}
