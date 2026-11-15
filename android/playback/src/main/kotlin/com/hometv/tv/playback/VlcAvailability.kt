package com.hometv.tv.playback

/** Whether libVLC can run on this device; false keeps every file on ExoPlayer (prd.md decision 11). */
fun interface VlcAvailability {
    fun isAvailable(): Boolean
}

/** Thrown by [LibVlcProvider.get] when LibVLC can't be created (the cause is logged once). */
class VlcUnavailableException(cause: Throwable) : RuntimeException(cause)

/**
 * Process-wide LibVLC, one instance per option list (the subtitle style is in the instance options,
 * `VlcOptions.libVlc(h, look)`). **Availability and creation are separate**:
 * - [availability] is cheap and never creates LibVLC or loads native code: true when [abis] contains
 *   armeabi-v7a or arm64-v8a **and** the first creation did not fail in this process. Safe on the main
 *   thread at any time.
 * - [get] / [getOrOld] create lazily, on the main thread, when a VLC controller opens a media. Equal options
 *   reuse the current instance. A `Throwable` from the **first** [factory] call is logged once, recorded
 *   ([availability] turns false for the rest of the process) and rethrown as [VlcUnavailableException]; every
 *   later call throws at once without calling [factory] again.
 * - Different options (a restyle) build a new instance, then [release] the old one (libVLC's native reference
 *   count keeps it alive while a player still holds it; the controller releases its `MediaPlayer` first). A
 *   failure there is not final: it is logged, the old instance is kept and returned with `false`, and that
 *   option list is remembered, so asking for it again returns the current instance at once (no second
 *   main-thread creation of a set known to fail); any other list is tried normally, a success clears it.
 *
 * Generic over the instance type so the JVM test needs no libVLC class (production: `LibVlcProvider<LibVLC>`).
 */
class LibVlcProvider<T : Any>(
    private val abis: List<String>,
    private val factory: (List<String>) -> T,
    private val release: (T) -> Unit,
    private val log: (String, Throwable?) -> Unit,
) {
    private var instance: T? = null
    private var instanceOptions: List<String>? = null
    private var failure: Throwable? = null
    private var lastFailedOptions: List<String>? = null

    val availability: VlcAvailability = VlcAvailability { failure == null && abis.any { it in ARM_ABIS } }

    /** The instance built with [options] (or the current one when building it failed, see [getOrOld]). */
    fun get(options: List<String>): T = getOrOld(options).first

    /** The instance for [options] and true, or the current instance and false when a new one could not be built. */
    fun getOrOld(options: List<String>): Pair<T, Boolean> {
        failure?.let { throw VlcUnavailableException(it) }
        val current = instance
        if (current == null) {
            return try {
                factory(options).also {
                    instance = it
                    instanceOptions = options.toList()
                } to true
            } catch (e: Throwable) {
                failure = e
                log("LibVLC unavailable", e)
                throw VlcUnavailableException(e)
            }
        }
        if (options == instanceOptions) return current to true
        if (options == lastFailedOptions) return current to false
        val next = try {
            factory(options)
        } catch (e: Throwable) {
            lastFailedOptions = options.toList()
            log("LibVLC restyle failed, the previous look is kept", e)
            return current to false
        }
        instance = next
        instanceOptions = options.toList()
        lastFailedOptions = null
        release(current)
        return next to true
    }

    private companion object {
        /** The ABIs the APK ships libVLC for (`ndk.abiFilters`). */
        val ARM_ABIS = setOf("armeabi-v7a", "arm64-v8a")
    }
}
