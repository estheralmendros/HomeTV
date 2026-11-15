package com.hometv.tv.data

import com.hometv.tv.core.api.EpisodeSummary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of pushed Player routes (process death): a route whose
 * `launchId` is not here was restored by Navigation after the process died, and the Player pops it
 * without any request. It also carries the Home episode payload (its `media_files`), so the Player
 * can send `POST .../playback` without waiting for `GET /api/episodes/{id}`.
 */
class PlayerLaunches {
    private class Launch(val payload: EpisodeSummary?)

    private val launches = ConcurrentHashMap<String, Launch>()

    fun register(payload: EpisodeSummary?): String = UUID.randomUUID().toString().also { launches[it] = Launch(payload) }

    fun isActive(launchId: String): Boolean = launches.containsKey(launchId)

    fun payload(launchId: String): EpisodeSummary? = launches[launchId]?.payload

    /** The Player route was popped. */
    fun finish(launchId: String) {
        launches.remove(launchId)
    }
}
