package app.listentogether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.TrackRef
import app.listentogether.playlist.PlaylistPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

/** 文字胶囊按钮：底部栏和表单里的主/次操作统一用它。 */
@Composable
private fun PillButton(text: String, background: Color, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** 歌单详情头部的小图标操作（收藏 / 刷新 / 多选）。 */
@Composable
private fun ActionPill(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when {
        active -> Palette.Accent
        enabled -> Palette.Text
        else -> Palette.Muted
    }
    Row(
        Modifier.clip(CircleShape)
            .background(if (active) Palette.AccentSoft else Palette.SurfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistPanel(
    controller: AppController,
    ui: UiActions,
    initialLink: String,
    added: Set<String>,
    onAdded: (TrackRef) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    inSheet: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val saved by controller.playlists.saved.collectAsState()
    val room by controller.room.collectAsState()
    var link by remember { mutableStateOf(initialLink) }
    var preview by remember { mutableStateOf<PlaylistPreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var adding by remember { mutableStateOf(false) }
    var addedCount by remember { mutableStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var addJob by remember { mutableStateOf<Job?>(null) }
    val pullState = rememberPullToRefreshState()
    val queued = room?.queue.orEmpty().mapTo(HashSet()) { it.track.key } + added
    val current = preview
    val available = current?.tracks.orEmpty().filterNot { it.key in queued }.distinctBy { it.key }
    val chosen = available.filter { it.key in selected }

    fun resolve(value: String, forceRefresh: Boolean = false) {
        if (value.isBlank() || adding) return
        // Manual pasting/opening also consumes the link, before clipboard auto-detection can run.
        ClipMemory.takeOnce(value.trim())
        loadJob?.cancel()
        link = value
        loading = true
        error = null
        selecting = false
        selected = emptySet()
        loadJob = scope.launch {
            try {
                val result = withTimeout(45_000) { controller.playlist(value, forceRefresh) }
                preview = result
                if (saved.any { it.key == result.saved().key }) controller.playlists.save(result.saved())
            } catch (_: TimeoutCancellationException) {
                error = "歌单读取超时，请稍后重试"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describeError(e)
            } finally { loading = false }
        }
    }

    fun add(tracks: List<TrackRef>) {
        if (adding || loading || tracks.isEmpty()) return
        adding = true
        addedCount = 0
        error = null
        addJob = scope.launch {
            var warmed = false
            try {
                for (track in tracks) {
                    if (controller.room.value?.queue.orEmpty().any { it.track.key == track.key }) {
                        selected = selected - track.key
                        continue
                    }
                    // Room messages share a 5/s limit with sync/status traffic.
                    if (addedCount > 0) delay(350)
                    try {
                        controller.addSong(track)
                    } catch (e: Exception) {
                        val msg = describeError(e)
                        if (!warmed && current != null && msg.contains("刷新歌单")) {
                            warmed = true
                            val fresh = withTimeout(45_000) { controller.playlist(current.url, forceRefresh = true) }
                            preview = fresh
                            controller.addSong(track)
                        } else {
                            throw e
                        }
                    }
                    onAdded(track)
                    selected = selected - track.key
                    addedCount++
                }
                if (selected.isEmpty()) selecting = false
                ui.toast("已点 $addedCount 首")
            } catch (e: CancellationException) {
                ui.toast("已点 $addedCount 首")
                throw e
            } catch (e: Exception) {
                error = "已确认点入 $addedCount 首。${describeError(e)}，请查看房间队列后重试。"
            } finally { adding = false }
        }
    }

    fun back() {
        loadJob?.cancel()
        addJob?.cancel()
        if (preview != null && initialLink.isBlank()) { preview = null; selecting = false; selected = emptySet(); error = null }
        else onBack()
    }
    fun closeToRoom() {
        loadJob?.cancel()
        addJob?.cancel()
        onClose()
    }
    PlatformBackHandler { back() }
    LaunchedEffect(Unit) { if (initialLink.isNotBlank()) resolve(initialLink) }

    val panelMod = if (inSheet) {
        Modifier.fillMaxSize().background(Palette.Background)
    } else {
        Modifier.fillMaxSize().background(Palette.Background).windowInsetsPadding(WindowInsets.systemBars)
    }
    Column(panelMod) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(onClick = { if (inSheet) back() else closeToRoom() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Palette.Text)
            }
            Text("点歌", modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        if (current == null) {
            OutlinedTextField(
                value = link, onValueChange = { link = it }, enabled = !loading,
                placeholder = { Text("粘贴网易云 / QQ 歌单链接或分享文案", color = Palette.Muted) },
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, tint = Palette.Muted) },
                trailingIcon = {
                    if (link.isNotBlank()) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).clickable { link = "" },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, contentDescription = "清空", tint = Palette.Muted, modifier = Modifier.size(16.dp)) }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(16.dp), maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Palette.Surface, focusedContainerColor = Palette.Surface,
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Palette.Accent,
                ),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("支持公开歌单，收藏保存在本机", Modifier.weight(1f), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                PillButton(
                    "打开歌单",
                    background = if (!loading && link.isNotBlank()) Palette.Accent else Palette.SurfaceHigh,
                    color = if (!loading && link.isNotBlank()) Color.White else Palette.Muted,
                    enabled = !loading && link.isNotBlank(),
                    onClick = { resolve(link) },
                )
            }
        } else {
            val info = current.saved()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(info.coverTrack(), 72.dp, corner = 12.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(info.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(info.sourceName, info.creator).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("读取 ${current.tracks.size} / ${current.total} 首", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val isSaved = saved.any { it.key == info.key }
                ActionPill(
                    if (isSaved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    if (isSaved) "已收藏" else "收藏歌单",
                    active = isSaved,
                ) {
                    if (isSaved) controller.playlists.remove(info.key) else controller.playlists.save(info)
                    ui.toast(if (isSaved) "已取消收藏" else "已收藏到本机")
                }
                ActionPill(Icons.Rounded.Refresh, "刷新", enabled = !loading && !adding) { resolve(current.url, forceRefresh = true) }
                ActionPill(
                    Icons.Rounded.Checklist, if (selecting) "取消多选" else "多选",
                    active = selecting, enabled = !loading && !adding,
                ) { selecting = !selecting; selected = emptySet() }
            }
            if (!current.complete) {
                Text(
                    "歌曲未全部读到，可能有权限限制或部分详情暂时不可用。可刷新重试，已读歌曲可以点歌。",
                    color = Palette.Accent, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        if (loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp),
                color = Palette.Accent, trackColor = Color.Transparent,
            )
        }
        error?.let {
            Text(
                it, color = Palette.Accent, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Palette.AccentSoft)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        if (current == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                if (saved.isEmpty()) {
                    item {
                        Text(
                            "把常听的歌单收藏在这里，下次直接挑歌。",
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    item {
                        Text(
                            "本机收藏 · ${saved.size}", color = Palette.Muted,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                }
                items(saved, key = { it.key }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .clickable(enabled = !loading) { resolve(playlist.url) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(playlist.coverTrack(), 52.dp, corner = 10.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(playlist.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${playlist.sourceName} · ${playlist.total} 首",
                                color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Palette.SurfaceHigh)
                                .clickable { controller.playlists.remove(playlist.key) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Close, "取消收藏", tint = Palette.Muted, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        } else {
            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { if (!adding) resolve(current.url, forceRefresh = true) },
                state = pullState,
                modifier = Modifier.weight(1f),
            ) {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = loading,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = Palette.SurfaceHigh,
                    color = Palette.Accent,
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    itemsIndexed(current.tracks, key = { index, track -> "$index:${track.key}" }) { _, track ->
                        TrackRow(track, track.key in queued, selection = if (selecting) track.key in selected else null,
                            enabled = !adding && !loading && (!selecting || track.key !in queued), onAdd = {
                                if (selecting) selected = if (track.key in selected) selected - track.key else selected + track.key
                                else add(listOf(track))
                            })
                    }
                }
            }
        }
        if (adding || selecting) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Palette.SurfaceHigh),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (adding) {
                        Text("正在点入 · 已点 $addedCount 首…", Modifier.weight(1f), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        PillButton("停止", background = Palette.Surface, color = Palette.Text) { addJob?.cancel() }
                    } else {
                        PillButton(
                            if (chosen.size == available.size && chosen.isNotEmpty()) "清空" else "全选可点",
                            background = Palette.Surface, color = Palette.Text,
                            enabled = !loading,
                        ) { selected = if (chosen.size == available.size) emptySet() else available.mapTo(HashSet()) { it.key } }
                        Spacer(Modifier.weight(1f))
                        PillButton(
                            "点入房间 · ${chosen.size} 首",
                            background = if (chosen.isNotEmpty() && !loading) Palette.Accent else Palette.Surface,
                            color = if (chosen.isNotEmpty() && !loading) Color.White else Palette.Muted,
                            enabled = chosen.isNotEmpty() && !loading,
                        ) { add(chosen) }
                    }
                }
            }
        }
    }
}
