package com.hometv.tv.data

import com.hometv.tv.core.api.ApiJson
import com.hometv.tv.core.api.EpisodeSummary
import com.hometv.tv.core.api.FilmSummary
import com.hometv.tv.core.api.HomeResponse
import com.hometv.tv.core.api.ShowSummary
import com.hometv.tv.core.api.Summary
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The three Home payloads of one load: `/api/home` and the Films/Series rows. */
@Serializable
data class HomePayload(
    val home: HomeResponse,
    val films: List<FilmSummary> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
) {
    /** Drops `type:id` everywhere (and, for a show, its episodes). */
    fun without(type: String, id: Long): HomePayload {
        val gone = { s: Summary -> s.key == "$type:$id" || (type == "show" && s is EpisodeSummary && s.show_id == id) }
        return copy(
            home = home.copy(
                continue_watching = home.continue_watching.filterNot(gone),
                recently_added = home.recently_added.filterNot(gone),
            ),
            films = films.filterNot(gone),
            shows = shows.filterNot(gone),
        )
    }
}

/**
 * Last Home payload per profile as JSON files `{dir}/{profileId}.json` (atomic write: temp + rename).
 * Disposable: a missing or unreadable file is just "no cache", a failing write is logged and
 * ignored; cleared on server change.
 */
class HomeCache(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String, Throwable?) -> Unit = { msg, e -> runCatching { Log.w("HomeTvCache", msg, e) } },
) {
    private val mutex = Mutex()

    suspend fun read(profileId: Long): HomePayload? = withContext(io) {
        mutex.withLock { readFile(file(profileId)) }
    }

    suspend fun write(profileId: Long, payload: HomePayload) = withContext(io) {
        mutex.withLock { safeWrite(file(profileId), payload) }
    }

    /** `AppEvent.ItemGone`: a cached card for a removed title must not reappear. */
    suspend fun remove(type: String, id: Long) = withContext(io) {
        mutex.withLock {
            dir.listFiles { f -> f.extension == "json" }.orEmpty().forEach { f ->
                readFile(f)?.let { safeWrite(f, it.without(type, id)) }
            }
        }
    }

    suspend fun clear() = withContext(io) {
        mutex.withLock { dir.listFiles().orEmpty().forEach { it.delete() } }
    }

    private fun file(profileId: Long) = File(dir, "$profileId.json")

    private fun readFile(f: File): HomePayload? = runCatching {
        if (!f.isFile) null else ApiJson.decodeFromString(HomePayload.serializer(), f.readText())
    }.getOrNull()

    /** The cache is disposable: a failing write (disk full, read-only) is logged, never thrown. */
    private fun safeWrite(f: File, payload: HomePayload) {
        try {
            writeFile(f, payload)
        } catch (e: IOException) {
            log("Home cache write failed: ${f.name}", e)
            File(dir, "${f.name}.tmp").delete()
        }
    }

    private fun writeFile(f: File, payload: HomePayload) {
        dir.mkdirs()
        val tmp = File(dir, "${f.name}.tmp")
        tmp.writeText(ApiJson.encodeToString(HomePayload.serializer(), payload))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }
}
