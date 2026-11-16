package app.listentogether.playlist

import app.listentogether.KeyValueStore
import app.listentogether.model.TrackRef
import app.listentogether.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class SavedPlaylist(
    val source: String,
    val id: String,
    val url: String,
    val name: String,
    val creator: String = "",
    val coverUrl: String? = null,
    val total: Int = 0,
) {
    val key: String get() = "$source:$id"
    val sourceName: String get() = if (source == "netease") "网易云" else "QQ 音乐"
    fun coverTrack() = TrackRef(source, id, coverUrl = coverUrl)
}

@Serializable
data class PlaylistPreview(
    val source: String,
    val id: String,
    val url: String,
    val name: String,
    val creator: String = "",
    val coverUrl: String? = null,
    val total: Int = 0,
    val complete: Boolean = false,
    val tracks: List<TrackRef> = emptyList(),
) {
    val key: String get() = "$source:$id"
    fun saved() = SavedPlaylist(source, id, url, name, creator, coverUrl, total)
}

/** Save references only; opening a saved list reads its current public contents again. */
class PlaylistLibrary(private val store: KeyValueStore) {
    private val serializer = ListSerializer(SavedPlaylist.serializer())
    private val previewSerializer = ListSerializer(PlaylistPreview.serializer())
    private val mutable = MutableStateFlow(runCatching {
        store.get(KEY)?.let { AppJson.decodeFromString(serializer, it) }.orEmpty()
    }.getOrDefault(emptyList()))
    val saved: StateFlow<List<SavedPlaylist>> = mutable

    /** 最近解析过的歌单内容（最多 [MAX_CACHED] 条），重复打开同一歌单不用等网络。 */
    private val previews = MutableStateFlow(loadPreviews())

    fun save(playlist: SavedPlaylist) = update(listOf(playlist) + mutable.value.filterNot { it.key == playlist.key })

    /** 删收藏的同时清掉它的预览缓存，避免之后还看到旧内容。 */
    fun remove(key: String) {
        update(mutable.value.filterNot { it.key == key })
        writePreviews(previews.value.filterNot { it.key == key })
    }

    /** 缓存一份解析结果；同一歌单只留最新一份，超过 [MAX_CACHED] 条丢最旧的。 */
    fun cache(preview: PlaylistPreview) =
        writePreviews(listOf(preview) + previews.value.filterNot { it.key == preview.key })

    fun cached(key: String): PlaylistPreview? = previews.value.firstOrNull { it.key == key }

    /** 按粘贴的链接找缓存：先精确比预览里的 url，再借收藏里的 url 找到对应歌单。 */
    fun cachedByUrl(url: String): PlaylistPreview? {
        val trimmed = url.trim()
        previews.value.firstOrNull { it.url == trimmed }?.let { return it }
        return saved.value.firstOrNull { it.url == trimmed }?.key?.let(::cached)
    }

    private fun update(value: List<SavedPlaylist>) {
        store.set(KEY, AppJson.encodeToString(serializer, value))
        mutable.value = value
    }

    private fun loadPreviews(): List<PlaylistPreview> = runCatching {
        store.get(CACHE_KEY)?.let { AppJson.decodeFromString(previewSerializer, it) }
            ?.take(MAX_CACHED).orEmpty()
    }.getOrDefault(emptyList())

    private fun writePreviews(value: List<PlaylistPreview>) {
        val capped = value.distinctBy { it.key }.take(MAX_CACHED)
        store.set(CACHE_KEY, AppJson.encodeToString(previewSerializer, capped))
        previews.value = capped
    }

    private companion object {
        const val KEY = "public_playlists_v1"
        const val CACHE_KEY = "playlist_cache_v1"
        const val MAX_CACHED = 20
    }
}

/** A routing hint for pasted content. The server validates hosts, IDs and redirects. */
object PlaylistLinks {
    private val link = Regex("""(?i)https?://(?:163cn\.tv/|(?:y\.)?music\.163\.com/(?:\#/)?(?:m/)?playlist(?:[/?])|(?:[a-z0-9-]+\.)?y\.qq\.com/(?:n/(?:ryqq|yqq)/playlist/|[^\s]*?(?:taoge|playlist)\.html\?|base/fcgi-bin/u\?))""")
    fun contains(text: String): Boolean = link.containsMatchIn(text)
}
