package app.listentogether

import app.listentogether.lyrics.Lyrics
import app.listentogether.model.Podcast
import app.listentogether.model.ProgramPage
import app.listentogether.model.RoomSummary
import app.listentogether.model.Snapshot
import app.listentogether.model.TrackRef
import app.listentogether.net.ApiException
import app.listentogether.net.AppJson
import app.listentogether.net.RoomSocket
import app.listentogether.net.ServerIdentity
import app.listentogether.net.ServerProofException
import app.listentogether.net.proveServer
import app.listentogether.net.ServerApi
import app.listentogether.net.SocketEvent
import app.listentogether.net.createHttpClient
import app.listentogether.player.AudioPlayer
import app.listentogether.playlist.PlaylistLibrary
import app.listentogether.playlist.PlaylistPreview
import app.listentogether.player.PlaybackCoordinator
import app.listentogether.player.SyncDebug
import app.listentogether.room.RoomStore
import app.listentogether.sync.ClockSync
import app.listentogether.sync.SyncMode
import app.listentogether.model.AudioQuality
import app.listentogether.model.LikeEvent
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.ChatMessage
import app.listentogether.model.ControlAction
import app.listentogether.model.ControlResult
import app.listentogether.model.RoomKind
import app.listentogether.model.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 本地持久化的简单键值存储（Android: SharedPreferences，iOS: NSUserDefaults）。 */
interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

/** 本机对某一首歌（seq）看到的点赞总数，含还没被服务器确认的。 */
data class LikeTally(val seq: Int = -1, val total: Int = 0)

/**
 * 在某台服务器上的身份。本机可以记着好几台（[AppController.servers]），同时只连一台。
 * @param publicKey 第一次加入时记下的服务器公钥（base64 DER）。之后不管地址怎么变，
 *   都要服务器用对应私钥签名证明是同一台，才会把 token 发过去。null 表示老记录或老服务器，还没钉住。
 */
@Serializable
data class Session(
    val baseUrl: String,
    val token: String,
    val userId: String,
    val nickname: String,
    val avatar: String = "",
    val serverId: String? = null,
    val publicKey: String? = null,
)

/**
 * App 的总控：保存登录信息，维持与服务器的长连接，把房间状态和播放协调器串起来。
 * UI 只读这里暴露的 StateFlow，并调用这里的方法。
 */
class AppController(
    private val store: KeyValueStore,
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
) {
    val playlists = PlaylistLibrary(store)
    private val http = createHttpClient()
    private val clock = ClockSync()
    private val roomStore = RoomStore()

    private val _session = MutableStateFlow(loadSession())
    /** 当前连着的服务器；null 时显示服务器列表/加入页。 */
    val session: StateFlow<Session?> = _session

    private val _servers = MutableStateFlow(loadServers())
    /** 本机记着的所有服务器身份，最近用过的在前。 */
    val servers: StateFlow<List<Session>> = _servers

    val room: StateFlow<Snapshot?> = roomStore.state

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** 给 UI 弹提示的文本：切歌通知、错误等。 */
    val messages: SharedFlow<String> = _messages

    private val _likes = MutableSharedFlow<LikeEvent>(extraBufferCapacity = 64)
    /** 房间里每一次点赞（包括自己的），UI 拿来放特效。 */
    val likes: SharedFlow<LikeEvent> = _likes

    private val _myLikes = MutableStateFlow(LikeTally())
    /**
     * 本机乐观的点赞计数：点一下立刻 +1，不等服务器。
     * 显示时取它和服务器广播的总数中较大的那个（别人的赞靠服务器那一路进来）。
     */
    val myLikes: StateFlow<LikeTally> = _myLikes
    private var pendingLikes = 0
    private var likeJob: Job? = null

    private val _danmaku = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    /** 放映厅里新到的朋友弹幕（含自己的），UI 拿来飘在画面上。 */
    val danmaku: SharedFlow<ChatMessage> = _danmaku

    private val _volume = MutableStateFlow(store.get(KEY_VOLUME)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f)
    /** 本机音量 0..1，只影响自己。 */
    val volume: StateFlow<Float> = _volume
    private val _muted = MutableStateFlow(store.get(KEY_MUTED) == "1")
    val muted: StateFlow<Boolean> = _muted

    private var api: ServerApi? = null
    private var socket: RoomSocket? = null

    private val _lossless = MutableStateFlow(store.get(KEY_QUALITY) == AudioQuality.LOSSLESS)
    /** 本机是否选了无损。只影响自己拿到的地址，别人照常 320k；选一次之后的歌都生效。 */
    val lossless: StateFlow<Boolean> = _lossless

    private val _videoQn = MutableStateFlow(store.get(KEY_VIDEO_QN)?.toIntOrNull())
    /** 放映厅本机选的画质（B 站 qn）；null 表示默认 1080P。只影响自己。 */
    val videoQn: StateFlow<Int?> = _videoQn

    private val _syncMode = MutableStateFlow(SyncMode.of(store.get(KEY_SYNC_MODE)))
    /** 本机的同步档位，默认宽松。只影响自己：每个人各自对齐服务器时间。 */
    val syncMode: StateFlow<SyncMode> = _syncMode

    private val coordinator = PlaybackCoordinator(
        scope = scope,
        room = roomStore.state,
        clock = clock,
        player = player,
        api = { api },
        quality = { if (_lossless.value) AudioQuality.LOSSLESS else AudioQuality.STANDARD },
        videoQn = { _videoQn.value },
        syncMode = _syncMode.value,
        reportStatus = { seq, status ->
            socket?.request("track_status", buildJsonObject {
                put("seq", seq)
                put("status", status)
            })
        },
    )
    val debug: StateFlow<SyncDebug> = coordinator.debug
    /** 本机被别的 App/拔耳机打断了，界面上提示"点这里恢复"。 */
    val interrupted: StateFlow<Boolean> = coordinator.interrupted

    /** 当前这首实际的音质（选了无损但这首没有无损时是 standard）。 */
    val playingQuality: StateFlow<String?> = coordinator.playingQuality

    /** 放映厅当前视频的画质和可选画质。 */
    val videoInfo: StateFlow<VideoInfo?> = coordinator.videoInfo
    /** 本机解析当前视频失败的原因（YouTube 连不上之类）。 */
    val videoError: StateFlow<String?> = coordinator.videoError

    /** 换画质并保存；当前视频马上按新画质重新加载，从房间当前进度接上。 */
    fun setVideoQn(qn: Int) {
        if (_videoQn.value == qn) return
        _videoQn.value = qn
        store.set(KEY_VIDEO_QN, qn.toString())
        coordinator.refresh()
    }

    /** 切换音质并保存；正在放的这首也马上按新音质重新加载（会从房间当前进度接上）。 */
    fun setSyncMode(mode: SyncMode) {
        if (_syncMode.value == mode) return
        _syncMode.value = mode
        store.set(KEY_SYNC_MODE, mode.key)
        coordinator.setSyncMode(mode)
    }

    fun setLossless(on: Boolean) {
        if (_lossless.value == on) return
        _lossless.value = on
        store.set(KEY_QUALITY, if (on) AudioQuality.LOSSLESS else null)
        coordinator.refresh()
    }

    fun resumePlayback() = coordinator.resume()

    fun refreshPlayback() = coordinator.refresh()

    init {
        applyVolume()
        coordinator.start()
        _session.value?.let { s -> scope.launch { reconnectVerified(s) } }
    }

    // ---------- 登录 ----------

    /**
     * 用邀请码加入。先让服务器证明身份：如果是本机记着的某台服务器（哪怕换了地址），
     * 直接沿用原来的身份，不消耗邀请码、不在服务器上多出一个新成员。
     */
    suspend fun join(baseUrl: String, inviteCode: String, nickname: String) {
        val url = normalizeBaseUrl(baseUrl)
        val identity = proveServer(ServerApi(http, url) { null })
        if (identity is ServerIdentity.Verified) {
            _servers.value.firstOrNull { it.publicKey == identity.publicKey }?.let { known ->
                val moved = known.copy(baseUrl = url, serverId = identity.serverId)
                try {
                    val me = ServerApi(http, url) { moved.token }.me()
                    activate(moved.copy(nickname = me.nickname, avatar = me.avatar))
                    say("认出了这台服务器，继续用你原来的身份「${me.nickname}」")
                    return
                } catch (e: ApiException) {
                    // 身份在服务器上被删了或停用了，这条记录作废，下面正常加入
                    if (e.status != 401 && e.status != 403) throw e
                    removeServer(known)
                }
            }
        }
        val result = ServerApi(http, url) { null }.join(inviteCode, nickname)
        val verified = identity as? ServerIdentity.Verified
        activate(Session(url, result.token, result.userId, result.nickname, serverId = verified?.serverId, publicKey = verified?.publicKey))
    }

    /**
     * 连回列表里的某台服务器，可以顺便换成新地址。记录里钉了公钥的，新地址上必须是同一台服务器；
     * 老记录没钉公钥，只允许在原地址连，并在这次顺便钉上。
     */
    suspend fun openServer(target: Session, newBaseUrl: String? = null) {
        val url = newBaseUrl?.let(::normalizeBaseUrl) ?: target.baseUrl
        activate(verifyAt(target, url))
    }

    /** 回到服务器列表，身份留着，随时点回来。 */
    fun switchServer() {
        socket?.stop()
        socket = null
        api = null
        roomStore.clear()
        store.set(KEY_ROOM, null)
        saveSession(null)
        _session.value = null
    }

    /** 从本机删掉这台服务器的身份（服务器上的成员记录不受影响）。 */
    fun forgetServer(target: Session) {
        removeServer(target)
        if (_session.value?.token == target.token) switchServer()
    }

    private suspend fun verifyAt(target: Session, url: String): Session {
        val identity = proveServer(ServerApi(http, url) { null })
        val pinned = target.publicKey
        if (pinned != null) {
            if (identity !is ServerIdentity.Verified || identity.publicKey != pinned) {
                throw ServerProofException("这个地址上不是你之前加入的那台服务器，为了安全没有使用你的身份")
            }
            return target.copy(baseUrl = url, serverId = identity.serverId)
        }
        if (url != target.baseUrl) {
            throw ServerProofException("这条记录来自旧版本，还没法确认新地址上是不是同一台服务器。请先用原地址连一次，或者用邀请码重新加入")
        }
        // 老记录在原地址上第一次核对成功：钉住公钥（首次信任）
        val verified = identity as? ServerIdentity.Verified
        return target.copy(serverId = verified?.serverId, publicKey = verified?.publicKey)
    }

    /** 启动时连上次的服务器：先核对身份，网络不通就隔一会儿重试，身份不符就退回列表。 */
    private suspend fun reconnectVerified(s: Session) {
        var backoff = 2_000L
        while (_session.value?.token == s.token) {
            try {
                val checked = verifyAt(s, s.baseUrl)
                if (_session.value?.token != s.token) return
                activate(checked)
                return
            } catch (e: ServerProofException) {
                switchServer()
                say(e.message ?: "服务器身份不符")
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000)
            }
        }
    }

    private fun activate(s: Session) {
        saveSession(s)
        upsertServer(s)
        _session.value = s
        connect(s)
    }

    private fun connect(s: Session) {
        socket?.stop()
        val server = ServerApi(http, s.baseUrl) { _session.value?.token }
        api = server
        val sock = RoomSocket(http, server.webSocketUrl(), { _session.value?.token }, clock, scope)
        sock.desiredRoom = store.get(KEY_ROOM)
        socket = sock
        scope.launch { sock.connected.collect { _connected.value = it } }
        scope.launch { sock.events.collect { onEvent(it) } }
        sock.start()
    }

    private fun onEvent(event: SocketEvent) {
        roomStore.apply(event)
        when (event) {
            is SocketEvent.Connected -> {
                // 资料可能在别的设备上改过，以服务器为准。
                _session.value?.let { updateSession(it.copy(nickname = event.nickname, avatar = event.avatar)) }
                if (event.roomError != null) leftRoom("之前的房间已经解散")
            }
            is SocketEvent.AuthFailed -> {
                _session.value?.let(::removeServer)
                switchServer()
                say("你在这台服务器上的身份已失效，请重新用邀请码加入")
            }
            is SocketEvent.Kicked -> leftRoom("你被房主移出了房间")
            is SocketEvent.ChatAdded -> if (event.message.isDanmaku) _danmaku.tryEmit(event.message)
            is SocketEvent.ControlResultReceived -> onControlResult(event.result)
            is SocketEvent.NoticeReceived -> event.notice.text.takeIf { it.isNotBlank() }?.let(::say)
            // 自己的赞点下去时已经放过特效了；别人连点攒成一批的，最多放 3 簇。
            is SocketEvent.LikeReceived -> if (event.like.userId != _session.value?.userId) {
                repeat(event.like.count.coerceIn(1, 3)) { _likes.tryEmit(event.like) }
            }
            else -> Unit
        }
    }

    private fun leftRoom(reason: String) {
        socket?.desiredRoom = null
        store.set(KEY_ROOM, null)
        roomStore.clear()
        say(reason)
    }

    // ---------- 房间 ----------

    suspend fun rooms(): List<RoomSummary> = requireApi().rooms()

    /** 新建房间：[RoomKind.MUSIC] 听歌，[RoomKind.VIDEO] 放映厅。 */
    suspend fun createRoom(kind: String = RoomKind.MUSIC) {
        val code = requireSocket().request("create_room", buildJsonObject { put("kind", kind) })
            .jsonObject["roomCode"]!!.jsonPrimitive.content
        rememberRoom(code)
    }

    suspend fun joinRoom(code: String) {
        requireSocket().request("join_room", buildJsonObject { put("roomCode", code.trim()) })
        rememberRoom(code.trim())
    }

    suspend fun leaveRoom() {
        runCatching { requireSocket().request("leave_room") }
        socket?.desiredRoom = null
        store.set(KEY_ROOM, null)
        roomStore.clear()
    }

    private fun rememberRoom(code: String) {
        socket?.desiredRoom = code
        store.set(KEY_ROOM, code)
    }

    // ---------- 歌单 ----------

    suspend fun search(query: String, source: String): List<TrackRef> = requireApi().search(query, source)

    /**
     * 解析歌单。默认优先用本机缓存（同一链接直接返回）；forceRefresh 时一定走服务器，
     * 并把最新结果写回缓存。
     */
    suspend fun playlist(link: String, forceRefresh: Boolean = false): PlaylistPreview {
        val trimmed = link.trim()
        if (!forceRefresh) playlists.cachedByUrl(trimmed)?.let { return it }
        val result = requireApi().playlist(link)
        playlists.cache(result)
        return result
    }

    suspend fun podcasts(query: String): List<Podcast> = requireApi().podcasts(query)

    suspend fun programs(podcastId: String, offset: Int = 0): ProgramPage = requireApi().programs(podcastId, offset)

    // 最近几首的歌词缓存（含"没有歌词"），收起再展开、来回切页面不用重新拉。
    private val lyricsCache = LinkedHashMap<String, Lyrics?>()

    /** 当前歌的歌词；没有或解析不出时间轴时为 null，网络失败抛异常（下次还会再试）。 */
    suspend fun lyrics(track: TrackRef): Lyrics? {
        if (track.key in lyricsCache) return lyricsCache[track.key]
        val parsed = requireApi().lyrics(track)?.let { Lyrics.parse(it) }
        lyricsCache[track.key] = parsed
        while (lyricsCache.size > 20) lyricsCache.remove(lyricsCache.keys.first())
        return parsed
    }

    suspend fun addSong(track: TrackRef): Int =
        requireSocket().request("add_song", buildJsonObject {
            put("source", track.source)
            put("id", track.id)
        }).jsonObject["itemId"]!!.jsonPrimitive.int

    suspend fun vote(itemId: Int, on: Boolean) {
        requireSocket().request("vote", buildJsonObject {
            put("itemId", itemId)
            put("on", on)
        })
    }

    suspend fun removeSong(itemId: Int) {
        requireSocket().request("remove_song", buildJsonObject { put("itemId", itemId) })
    }

    /** 投票跳过当前歌：只有自己在听时一票即跳，两人及以上需要两票。 */
    suspend fun skip(on: Boolean) {
        val seq = room.value?.playback?.seq ?: return
        requireSocket().request("skip", buildJsonObject {
            put("seq", seq)
            put("on", on)
        })
    }

    /**
     * 给当前歌点个赞，可以连点、不限次数。
     * 数字和特效立即在本机生效；请求第一下马上发，之后的连点每 [LIKE_BATCH_MS] 攒成一批发，
     * 既不会被服务器的频率限制吞掉，也不会一下一个包。
     */
    fun like() {
        val pb = room.value?.playback ?: return
        // 放映厅暂停时也能点赞。
        if ((pb.status != PlaybackStatus.PLAYING && pb.status != PlaybackStatus.PAUSED) || pb.item == null) return
        val me = _session.value?.userId ?: return
        val tally = _myLikes.value
        val base = if (tally.seq == pb.seq) maxOf(tally.total, pb.likes) else pb.likes
        _myLikes.value = LikeTally(pb.seq, base + 1)
        _likes.tryEmit(LikeEvent(me, pb.seq))
        pendingLikes++
        if (likeJob?.isActive != true) likeJob = scope.launch { flushLikes(pb.seq) }
    }

    private suspend fun flushLikes(seq: Int) {
        while (pendingLikes > 0) {
            val n = pendingLikes
            pendingLikes = 0
            val total = runCatching {
                requireSocket().request("like", buildJsonObject {
                    put("seq", seq)
                    put("count", n)
                }).jsonObject["likes"]?.jsonPrimitive?.int
            }.getOrNull()
            // 全部发完后以服务器为准校正一次（比如歌已经切了、请求被拒）。
            if (total != null && pendingLikes == 0 && _myLikes.value.seq == seq) _myLikes.value = LikeTally(seq, total)
            delay(LIKE_BATCH_MS)
        }
    }

    // ---------- 放映厅 ----------

    /** 自己现在能不能直接控制进度；不能时点按钮会变成请求。 */
    fun canControl(): Boolean {
        val r = room.value ?: return false
        val me = _session.value?.userId ?: return false
        return r.video?.canControl(me, r.playback.item?.requestedBy) ?: false
    }

    /**
     * 暂停、继续、拖进度、切下一个。没权限时服务器把它变成请求，返回 true，
     * 等有权限的人同意；结果通过消息提示。
     */
    suspend fun control(action: String, positionMs: Long = 0): Boolean {
        val seq = room.value?.playback?.seq ?: return false
        val d = requireSocket().request("control", buildJsonObject {
            put("action", action)
            put("seq", seq)
            if (action == ControlAction.SEEK) put("positionMs", positionMs.coerceAtLeast(0))
        }).jsonObject
        val requested = d["requested"]?.jsonPrimitive?.content == "true"
        if (requested) say(requestSentText(action, positionMs))
        return requested
    }

    suspend fun replyRequest(requestId: Int, accept: Boolean) {
        requireSocket().request("control_reply", buildJsonObject {
            put("requestId", requestId)
            put("accept", accept)
        })
    }

    /** 管理员改这个房间的档位。 */
    suspend fun setPolicy(policy: String) {
        requireSocket().request("set_policy", buildJsonObject { put("policy", policy) })
    }

    /** 管理员禁言：minutes 为 0 解除，-1 一直禁言到解除。 */
    suspend fun mute(userId: String, minutes: Int) {
        requireSocket().request("mute", buildJsonObject { put("userId", userId); put("minutes", minutes) })
    }

    /** nonce 是这条弹幕的编号：回执丢了用同一个编号重发，服务器只算一次（旧服务器会忽略它）。 */
    suspend fun sendDanmaku(text: String, nonce: String) {
        requireSocket().request("danmaku", buildJsonObject { put("text", text); put("nonce", nonce) })
    }

    /** 放映厅里此刻应在的位置（毫秒），进度条显示用。 */
    fun videoPositionMs(): Long {
        val pb = room.value?.playback ?: return 0
        return when (pb.status) {
            PlaybackStatus.PAUSED -> pb.positionMs ?: 0
            PlaybackStatus.PLAYING -> pb.startAt?.let { (clock.serverNowMs() - it).coerceAtLeast(0) } ?: 0
            else -> 0
        }
    }

    private fun onControlResult(r: ControlResult) {
        // 结果只告诉发起人；同意的情况消息流里已经公开说了。
        if (r.userId != _session.value?.userId) return
        when (r.result) {
            "rejected" -> say("${r.byNickname.ifEmpty { "对方" }} 拒绝了你的请求")
            "expired" -> say("没人回应你的请求")
        }
    }

    private fun requestSentText(action: String, positionMs: Long) = when (action) {
        ControlAction.PAUSE -> "已请求暂停，等有权限的人同意"
        ControlAction.PLAY -> "已请求继续播放，等有权限的人同意"
        ControlAction.SEEK -> "已请求跳到 ${formatClock(positionMs)}，等有权限的人同意"
        ControlAction.NEXT -> "已请求切到下一个，等有权限的人同意（也可以投票切到下一个）"
        else -> "已发出请求"
    }

    // ---------- 音量（只在本机） ----------

    fun setVolume(v: Float) {
        _volume.value = v.coerceIn(0f, 1f)
        // 拖动音量条时顺手解除静音。
        if (_muted.value && v > 0f) _muted.value = false
        applyVolume()
        store.set(KEY_VOLUME, _volume.value.toString())
        store.set(KEY_MUTED, if (_muted.value) "1" else null)
    }

    fun toggleMute() {
        _muted.value = !_muted.value
        applyVolume()
        store.set(KEY_MUTED, if (_muted.value) "1" else null)
    }

    private fun applyVolume() {
        player.setVolume(if (_muted.value) 0f else _volume.value)
    }

    fun serverNowMs(): Long = clock.serverNowMs()

    // ---------- 个人资料 ----------

    suspend fun updateProfile(nickname: String?, avatar: String?) {
        val me = requireApi().updateProfile(nickname, avatar)
        _session.value?.let { updateSession(it.copy(nickname = me.nickname, avatar = me.avatar)) }
    }

    suspend fun uploadAvatar(image: ByteArray) {
        val me = requireApi().uploadAvatar(image)
        _session.value?.let { updateSession(it.copy(nickname = me.nickname, avatar = me.avatar)) }
    }

    fun avatarUrl(userId: String, avatar: String): String? = api?.avatarUrl(userId, avatar)

    private fun updateSession(s: Session) {
        saveSession(s)
        upsertServer(s)
        _session.value = s
    }

    private fun upsertServer(s: Session) {
        _servers.value = listOf(s) + _servers.value.filter { it.token != s.token }
        saveServers()
    }

    private fun removeServer(s: Session) {
        _servers.value = _servers.value.filter { it.token != s.token }
        saveServers()
    }

    // ---------- 内部 ----------

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    private fun requireApi() = api ?: error("尚未登录")
    private fun requireSocket() = socket ?: error("尚未登录")

    private fun loadSession(): Session? {
        val base = store.get(KEY_BASE) ?: return null
        val token = store.get(KEY_TOKEN) ?: return null
        return Session(
            base, token, store.get(KEY_USER).orEmpty(), store.get(KEY_NICK).orEmpty(), store.get(KEY_AVATAR).orEmpty(),
            serverId = store.get(KEY_SERVER_ID), publicKey = store.get(KEY_SERVER_KEY),
        )
    }

    private fun loadServers(): List<Session> {
        val saved = store.get(KEY_SERVERS)?.let { runCatching { AppJson.decodeFromString(serverList, it) }.getOrNull() } ?: emptyList()
        // 老版本只记着一台：搬进列表
        val active = _session.value
        return if (active != null && saved.none { it.token == active.token }) listOf(active) + saved else saved
    }

    private fun saveServers() {
        store.set(KEY_SERVERS, AppJson.encodeToString(serverList, _servers.value))
    }

    private fun saveSession(s: Session?) {
        store.set(KEY_BASE, s?.baseUrl)
        store.set(KEY_TOKEN, s?.token)
        store.set(KEY_USER, s?.userId)
        store.set(KEY_NICK, s?.nickname)
        store.set(KEY_AVATAR, s?.avatar)
        store.set(KEY_SERVER_ID, s?.serverId)
        store.set(KEY_SERVER_KEY, s?.publicKey)
    }

    companion object {
        private const val KEY_BASE = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user_id"
        private const val KEY_NICK = "nickname"
        private const val KEY_ROOM = "room_code"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_SERVER_ID = "server_id"
        private const val KEY_SERVER_KEY = "server_public_key"
        private const val KEY_SERVERS = "servers"
        private val serverList = ListSerializer(Session.serializer())
        private const val KEY_VOLUME = "volume"
        private const val KEY_MUTED = "muted"
        private const val KEY_QUALITY = "audio_quality"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_VIDEO_QN = "video_qn"
        private const val LIKE_BATCH_MS = 250L

        fun formatClock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "${s / 3600}:${(s / 60 % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
            else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }

        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            return url
        }
    }
}
