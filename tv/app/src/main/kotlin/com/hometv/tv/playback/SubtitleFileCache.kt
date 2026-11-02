package com.hometv.tv.playback

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where side-loaded WebVTT files live; faked or backed by a temp dir in tests. */
interface SubtitleStore {
    /** The `file://` URI of the cached VTT, or null when it is not cached. Called on the main thread: no disk I/O. */
    fun get(mediaFileId: Long, trackId: String): String?

    /** Writes [bytes] atomically (temp file + rename) and returns the `file://` URI. */
    fun put(mediaFileId: Long, trackId: String, bytes: ByteArray): String

    /** Drops the VTT from the lookups at once ([get] returns null); no disk I/O. */
    fun forget(mediaFileId: Long, trackId: String)

    /** [forget] and delete the file (disk I/O: call it on an I/O dispatcher). */
    fun remove(mediaFileId: Long, trackId: String)
}

/**
 * `cacheDir/subtitles/{mediaFileId}/{trackId}.vtt`, cleared at app start and capped at [capBytes].
 * Eviction: after a write over the cap, the least recently
 * written files of **other** media files go first; the files of the media file being written are never
 * evicted (they may be attached to the playing `MediaItem`; one file's subtitles are small).
 * [get] answers from an in-memory index of the files this cache wrote (nothing survives a process),
 * so the Player's state machine can call it on the main thread; [put] and [remove] touch the disk.
 */
class SubtitleFileCache(private val root: File, private val capBytes: Long = DEFAULT_CAP_BYTES) : SubtitleStore {
    private val index: MutableSet<File> = ConcurrentHashMap.newKeySet()

    override fun get(mediaFileId: Long, trackId: String): String? = fileFor(mediaFileId, trackId).takeIf { it in index }?.let(::uriOf)

    @Synchronized
    override fun put(mediaFileId: Long, trackId: String, bytes: ByteArray): String {
        val target = fileFor(mediaFileId, trackId)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot write $target" }
        }
        index += target
        evict(keep = mediaFileId)
        return uriOf(target)
    }

    override fun forget(mediaFileId: Long, trackId: String) {
        index -= fileFor(mediaFileId, trackId)
    }

    @Synchronized
    override fun remove(mediaFileId: Long, trackId: String) {
        forget(mediaFileId, trackId)
        fileFor(mediaFileId, trackId).delete()
    }

    /** App start: nothing survives a process. */
    @Synchronized
    fun clear() {
        index.clear()
        root.deleteRecursively()
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    private fun evict(keep: Long) {
        var total = sizeBytes()
        if (total <= capBytes) return
        val candidates = files().filter { it.parentFile?.name != keep.toString() }.sortedBy { it.lastModified() }
        for (file in candidates) {
            if (total <= capBytes) break
            total -= file.length()
            index -= file
            file.delete()
        }
    }

    private fun files(): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "vtt" }.toList()

    private fun fileFor(mediaFileId: Long, trackId: String): File = File(File(root, mediaFileId.toString()), "${safe(trackId)}.vtt")

    private fun safe(trackId: String) = trackId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun uriOf(file: File): String = "file://" + file.absolutePath

    companion object {
        const val DEFAULT_CAP_BYTES = 20L * 1024 * 1024
    }
}
