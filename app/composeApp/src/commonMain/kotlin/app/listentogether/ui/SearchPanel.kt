package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.Podcast
import app.listentogether.model.TrackRef
import app.listentogether.playlist.PlaylistLinks
import app.listentogether.playlist.SavedPlaylist

private const val PodcastTab = "podcast"

// netease_voice 搜网易云播客里的单条声音；"播客"标签搜的是整档播客，点进去再挑节目。
private val Sources = listOf(
    "netease" to "网易云", "qq" to "QQ 音乐", "kugou" to "酷狗", "bilibili" to "B站",
    "netease_voice" to "声音", PodcastTab to "播客",
)

// 放映厅能点的平台。YouTube 可以按关键词搜，也能粘贴链接；B 站只认链接。
private val VideoTabs = listOf("bilibili" to "B站", "youtube" to "YouTube")

// 粘贴的是 B 站分享文案、链接或 BV 号时，不管在哪个标签都按 B 站解析。
internal val BilibiliLink = Regex("""b23\.tv/|bilibili\.com/video/|BV[0-9A-Za-z]{10}""")
internal val YouTubeLink = Regex("""(?i)(youtube\.com/(watch\?|shorts/|live/|embed/)|youtu\.be/)""")

/** 粘贴内容里认出的视频平台；放映厅里只认这两家。 */
internal fun videoLinkSource(text: String): String? = when {
    BilibiliLink.containsMatchIn(text) -> "bilibili"
    YouTubeLink.containsMatchIn(text) -> "youtube"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(controller: AppController, ui: UiActions, visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.Background,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier.padding(vertical = 10.dp).size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape).background(Palette.Outline),
            )
        },
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            SearchPanel(controller, ui, onClose = onDismiss, inSheet = true)
        }
    }
}

@Composable
fun SearchPanel(controller: AppController, ui: UiActions, onClose: () -> Unit, videoOnly: Boolean = false, inSheet: Boolean = false) {
    var query by remember { mutableStateOf("") }
    // 放映厅只能点视频：标签换成 B站 和 YouTube。
    val sources = if (videoOnly) VideoTabs else Sources
    var source by remember { mutableStateOf(sources.first().first) }
    var results by remember { mutableStateOf<List<TrackRef>>(emptyList()) }
    var podcasts by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    // 点过的歌放在这里，从播客详情回来后仍然显示"已点"。
    var added by remember { mutableStateOf(setOf<String>()) }
    var openPodcast by remember { mutableStateOf<Podcast?>(null) }
    var searchNow by remember { mutableStateOf(false) }
    var playlistMode by remember { mutableStateOf(false) }
    var playlistDraft by remember { mutableStateOf("") }
    // 本机收藏的歌单：没搜索、输入框空着的时候直接展示在这里。
    val savedPlaylists by controller.playlists.saved.collectAsState()

    // Keep the clipboard observer mounted across child pages; returning must not replay the same event.
    val linkSource = { text: String -> videoLinkSource(text)?.takeIf { videoOnly || it == "bilibili" } }
    val clip = rememberClipboardText()
    LaunchedEffect(clip) {
        if (playlistMode || openPodcast != null) return@LaunchedEffect
        val c = clip?.trim().orEmpty()
        if (!videoOnly && query.isBlank() && PlaylistLinks.contains(c) && ClipMemory.takeOnce(c)) {
            playlistDraft = c
            playlistMode = true
            return@LaunchedEffect
        }
        val from = linkSource(c)
        if (c.isNotEmpty() && query.isBlank() && from != null && ClipMemory.takeOnce(c)) {
            query = c
            ui.toast(if (from == "youtube") "已从剪贴板填入 YouTube 链接" else "已从剪贴板填入 B 站链接")
            searchNow = true
        }
    }


    if (playlistMode && !videoOnly) {
        PlaylistPanel(controller, ui, playlistDraft, added, onAdded = { added = added + it.key },
            onBack = { playlistMode = false; playlistDraft = "" }, onClose = onClose, inSheet = inSheet)
        return
    }

    PlatformBackHandler { if (openPodcast != null) openPodcast = null else onClose() }

    val addTrack = { track: TrackRef ->
        ui.run {
            controller.addSong(track)
            added = added + track.key
            ui.toast("已点：${track.title}")
        }
    }

    openPodcast?.let { p ->
        PodcastDetail(controller, ui, p, added, onAdd = addTrack, onBack = { openPodcast = null }, inSheet = inSheet)
        return
    }


    fun search() {
        if (query.isBlank()) return
        if (!videoOnly && PlaylistLinks.contains(query)) {
            ClipMemory.takeOnce(query.trim())
            playlistDraft = query
            playlistMode = true
            return
        }
        linkSource(query)?.let { source = it }
        loading = true
        val tab = source
        ui.run {
            try {
                if (tab == PodcastTab) podcasts = controller.podcasts(query) else results = controller.search(query, tab)
            } finally {
                loading = false
                searched = true
            }
        }
    }

    LaunchedEffect(searchNow) {
        if (searchNow) {
            searchNow = false
            search()
        }
    }

    val containerMod = if (inSheet) {
        Modifier.fillMaxSize().background(Palette.Background)
    } else {
        Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars)
    }
    Column(containerMod) {
        if (inSheet) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (videoOnly) "点视频" else "点歌", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                RoundIcon(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Palette.Muted) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Palette.Text) }
                Text(if (videoOnly) "点视频" else "点歌", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    // 一次多出一大段、里面有视频链接，就是粘贴进来的：不用再点搜索，直接解析。
                    val pasted = it.length - query.length > 8 && (linkSource(it) != null || (!videoOnly && PlaylistLinks.contains(it)))
                    query = it
                    if (pasted) search()
                },
                singleLine = true,
                placeholder = {
                    Text(
                        when (source) {
                            PodcastTab -> "搜播客名、主播"
                            "netease_voice" -> "搜声音：翻唱、现场、有声书…"
                            "bilibili" -> "粘贴 B 站视频链接或 BV 号"
                            "youtube" -> "搜 YouTube，或粘贴视频链接"
                            else -> "搜歌名、歌手，或粘贴歌单链接"
                        },
                        color = Palette.Muted,
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Palette.Muted) },
                trailingIcon = {
                    // 清空搜索即回到"我的歌单"待机视图。
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).clickable {
                                query = ""
                                searched = false
                                results = emptyList()
                                podcasts = emptyList()
                            },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, contentDescription = "清空", tint = Palette.Muted, modifier = Modifier.size(16.dp)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Palette.Surface, focusedContainerColor = Palette.Surface,
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.Accent,
                ),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { (key, label) ->
                val selected = source == key
                Text(
                    label,
                    color = if (selected) Color.White else Palette.Muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (selected) Palette.Accent else Palette.Surface)
                        .clickable {
                            if (source != key) {
                                source = key
                                searched = false
                            }
                            search()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp),
                color = Palette.Accent, trackColor = Color.Transparent,
            )
        }
        if (videoOnly && source == "youtube") {
            Text(
                "YouTube 视频由每个人的手机自己连 YouTube 播放，大家都要能翻墙才看得到",
                style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        val isPodcastTab = source == PodcastTab
        if (searched && !loading && (if (isPodcastTab) podcasts.isEmpty() else results.isEmpty())) {
            EmptyHint(
                when {
                    isPodcastTab -> "没有搜到播客，换个关键词试试"
                    videoOnly && source == "youtube" -> "没有搜到能看的 YouTube 视频，换个关键词或粘贴链接试试"
                    videoOnly -> "没认出视频，粘贴 B 站分享链接或 BV 号试试"
                    else -> "没有搜到能播放的歌，换个关键词或平台试试"
                },
            )
        }
        // 待机视图：没搜过且输入框为空时，这里就是"我的歌单"。
        val idlePlaylists = !videoOnly && query.isBlank() && !searched
        if (idlePlaylists) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的歌单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (savedPlaylists.isNotEmpty()) {
                    Text(
                        " · ${savedPlaylists.size}", color = Palette.Muted,
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(CircleShape).background(Palette.SurfaceHigh)
                        .clickable { playlistDraft = ""; playlistMode = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入歌单", color = Palette.Text, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (idlePlaylists) {
                if (savedPlaylists.isEmpty()) {
                    item(key = "idle:empty") { EmptyHint("粘贴网易云 / QQ 歌单链接，或直接搜歌") }
                }
                items(savedPlaylists, key = { it.key }) { playlist -> SavedPlaylistRow(playlist, onOpen = {
                    playlistDraft = playlist.url
                    playlistMode = true
                }, onRemove = { controller.playlists.remove(playlist.key) })
                }
            } else if (isPodcastTab) {
                items(podcasts, key = { "pod:" + it.id }) { p -> PodcastRow(p) { openPodcast = p } }
            } else {
                items(results, key = { it.key }) { track -> TrackRow(track, track.key in added) { addTrack(track) } }
            }
        }
    }
}

@Composable
private fun PodcastDetail(
    controller: AppController,
    ui: UiActions,
    podcast: Podcast,
    added: Set<String>,
    onAdd: (TrackRef) -> Unit,
    onBack: () -> Unit,
    inSheet: Boolean = false,
) {
    var tracks by remember(podcast.id) { mutableStateOf<List<TrackRef>>(emptyList()) }
    var nextOffset by remember(podcast.id) { mutableStateOf(0) }
    var hasMore by remember(podcast.id) { mutableStateOf(true) }
    var total by remember(podcast.id) { mutableStateOf(podcast.programCount) }
    var loading by remember(podcast.id) { mutableStateOf(false) }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        ui.run {
            try {
                val page = controller.programs(podcast.id, nextOffset)
                // 翻页时新节目可能把旧的挤到下一页，按 key 去重。
                val seen = tracks.mapTo(HashSet()) { it.key }
                tracks = tracks + page.tracks.filter { seen.add(it.key) }
                nextOffset = page.nextOffset
                hasMore = page.hasMore && page.tracks.isNotEmpty()
                total = page.total
            } catch (e: Throwable) {
                hasMore = false
                throw e
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(podcast.id) { loadMore() }

    val detailMod = if (inSheet) {
        Modifier.fillMaxSize().background(Palette.Background)
    } else {
        Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars)
    }
    Column(detailMod) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = Palette.Text) }
            Text(
                "播客", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(podcast.coverTrack(), 88.dp, corner = 14.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(podcast.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(podcast.dj, podcast.category).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
                Text("共 $total 期，最新的在前", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (loading && tracks.isEmpty()) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp),
                color = Palette.Accent, trackColor = Color.Transparent,
            )
        }
        if (!loading && !hasMore && tracks.isEmpty()) EmptyHint("这个播客没有能放的节目")
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(tracks, key = { it.key }) { track -> TrackRow(track, track.key in added) { onAdd(track) } }
            if (hasMore && tracks.isNotEmpty()) {
                item(key = "more") {
                    // 滚到底部这一行出现时自动加载下一页。
                    LaunchedEffect(nextOffset) { loadMore() }
                    Text(
                        if (loading) "正在加载…" else "加载更多",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !loading) { loadMore() }.padding(vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TrackRow(track: TrackRef, done: Boolean, selection: Boolean? = null, enabled: Boolean = true, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            // 多选模式下整行可点，勾哪都行。
            .clickable(enabled = selection != null && enabled, onClick = onAdd)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track, 52.dp, corner = 10.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artistText, track.album?.takeIf { it.isNotBlank() }).joinToString(" · ") + " · " + formatTime(track.durationMs),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (selection != null) {
            // 圆形选择指示：选中 Accent 实心 + 白色勾，未选中细描边。
            Box(
                Modifier.padding(end = 4.dp).size(24.dp).alpha(if (enabled) 1f else 0.4f)
                    .clip(CircleShape)
                    .background(if (selection) Palette.Accent else Color.Transparent)
                    .border(1.5.dp, if (selection) Palette.Accent else Palette.Muted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selection) Icon(Icons.Rounded.Check, contentDescription = "已选", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        } else Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (done) Palette.SurfaceHigh else Palette.AccentSoft)
                .clickable(enabled = !done && enabled, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (done) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = if (done) "已点" else "点歌",
                tint = if (done) Palette.Muted else Palette.Accent,
            )
        }
    }
}

@Composable
private fun PodcastRow(p: Podcast, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(p.coverTrack(), 56.dp, corner = 12.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(p.dj, "${p.programCount} 期").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = "查看节目", tint = Palette.Muted)
    }
}

/** "我的歌单"里的一条收藏：点开进入歌单详情，右侧小圆按钮取消收藏。 */
@Composable
private fun SavedPlaylistRow(playlist: SavedPlaylist, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(playlist.coverTrack(), 52.dp, corner = 10.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(playlist.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${playlist.sourceName} · ${playlist.total} 首",
                style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Palette.SurfaceHigh).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, contentDescription = "取消收藏", tint = Palette.Muted, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp), textAlign = TextAlign.Center,
    )
}

/** 播客封面借用歌曲封面组件：同样是网易云图片，同样按尺寸缩图。 */
private fun Podcast.coverTrack() = TrackRef(source = "netease", id = "podcast:$id", coverUrl = coverUrl)
