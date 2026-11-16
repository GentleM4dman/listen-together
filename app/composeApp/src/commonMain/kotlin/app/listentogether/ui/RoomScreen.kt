package app.listentogether.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.listentogether.model.AudioQuality
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.listentogether.AppController
import app.listentogether.model.MemberView
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.QueueItem
import app.listentogether.model.Snapshot
import app.listentogether.model.TrackRef
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun RoomScreen(controller: AppController, room: Snapshot, ui: UiActions, onMinimize: () -> Unit, onLeave: () -> Unit) {
    val session by controller.session.collectAsState()
    val connected by controller.connected.collectAsState()
    val me = session?.userId.orEmpty()
    var searching by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var showActivity by remember { mutableStateOf(false) }
    var showListeners by remember { mutableStateOf(false) }
    var showSyncMode by remember { mutableStateOf(false) }
    val syncMode by controller.syncMode.collectAsState()
    var showMenu by remember { mutableStateOf(false) }
    // 点赞按钮中心（根坐标），大拇指从这里飘出；不需要触发重组，用普通对象存。
    val likeAnchor = remember { arrayOfNulls<Offset>(1) }
    val likeFlow = remember(controller) { controller.likes.map { it.userId } }

    // 返回只是收起房间，音乐照常放；真正退出走右上角菜单、迷你栏的 × 或通知栏。
    PlatformBackHandler(enabled = !searching && !showActivity && !showListeners && !showSyncMode, onBack = onMinimize)
    if (showActivity) {
        ActivityScreen(controller, room, onClose = { showActivity = false })
        return
    }

    val membersById = room.members.associateBy { it.userId }
    val online = room.members.filter { it.online }
    val photo = { m: MemberView -> controller.avatarUrl(m.userId, m.avatar) }
    val track = room.playback.item?.track
    val lyrics by rememberLyrics(controller, track)

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Backdrop(track)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundIcon(onClick = onMinimize) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起", tint = Palette.Text)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("房间 ${room.roomCode}", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConnectionDot(connected)
                            Text(
                                if (connected) "${online.size} 人在线" else "正在重连…",
                                style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
                            )
                        }
                    }
                    AudioOutputButton(controller, Modifier.padding(end = 8.dp))
                    RoundIcon(onClick = { showActivity = true }) {
                        Icon(Icons.Rounded.History, contentDescription = "房间记录", tint = Palette.Text)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        RoundIcon(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = Palette.Text)
                        }
                        DropdownMenu(
                            expanded = showMenu, onDismissRequest = { showMenu = false },
                            containerColor = Palette.SurfaceHigh, shape = RoundedCornerShape(14.dp),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("同步档位")
                                        Text(" · ${syncMode.title}", color = Palette.Muted)
                                    }
                                },
                                leadingIcon = { Icon(Icons.Rounded.Sync, contentDescription = null, tint = Palette.Muted) },
                                onClick = { showMenu = false; showSyncMode = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (showDebug) "关闭同步调试" else "同步调试") },
                                leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = Palette.Muted) },
                                onClick = { showDebug = !showDebug; showMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text("刷新播放") },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Palette.Muted) },
                                onClick = { showMenu = false; controller.refreshPlayback() },
                            )
                            DropdownMenuItem(
                                text = { Text("退出房间", color = Palette.Accent) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, tint = Palette.Accent) },
                                onClick = { showMenu = false; onLeave() },
                            )
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(showDebug, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    DebugPanel(controller)
                }
            }
            item {
                NowPlaying(
                    controller, room, me, online, membersById, photo, ui,
                    lyrics = lyrics,
                    onPick = { searching = true }, onLikeAnchor = { likeAnchor[0] = it }, onShowListeners = { showListeners = true },
                )
            }

            room.next?.let { next ->
                item { SectionHeader("下一首", "已锁定，马上播放") }
                item(key = "next-${next.itemId}") {
                    QueueRow(next, membersById, photo, me, locked = true, controller, ui, Modifier.animateItem())
                }
            }
            if (room.queue.isNotEmpty() || room.next != null || track != null) {
                item { SectionHeader("待播 ${room.queue.size}", "置顶票多的先放，同票按点歌先后") }
            }
            items(room.queue, key = { it.itemId }) { item ->
                QueueRow(item, membersById, photo, me, locked = false, controller, ui, Modifier.animateItem())
            }
            if (room.queue.isEmpty() && track != null) {
                item {
                    Text(
                        "还没有下一首，点一首吧",
                        color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Box(
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(20.dp),
        ) {
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.pressScale(press).shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                    .background(Palette.AccentGradient).pressClick(press) { searching = true }
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("点歌", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        LikeBurstLayer(likeFlow, anchor = { likeAnchor[0] })
        ListenersSheet(controller, room, me, visible = showListeners, onDismiss = { showListeners = false })
        SyncModeSheet(controller, visible = showSyncMode, onDismiss = { showSyncMode = false })
        SearchSheet(controller, ui, visible = searching, onDismiss = { searching = false })
    }
}

/** 顶部用当前封面做一层模糊背景，向下渐隐到底色。 */
@Composable
private fun Backdrop(track: TrackRef?) {
    Box(Modifier.fillMaxWidth().height(520.dp)) {
        Crossfade(coverUrl(track, 200), animationSpec = tween(600), label = "backdrop") { url ->
            if (url != null) {
                AsyncImage(
                    model = url, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(70.dp).alpha(0.55f),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Palette.Background.copy(alpha = 0.25f), 1f to Palette.Background),
            ),
        )
    }
}

@Composable
internal fun RoundIcon(onClick: () -> Unit, active: Boolean = false, content: @Composable () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(press, 0.88f).size(40.dp).clip(CircleShape)
            .background(if (active) Palette.AccentSoft else Color.White.copy(alpha = 0.08f)).pressClick(press, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NowPlaying(
    controller: AppController,
    room: Snapshot,
    me: String,
    online: List<MemberView>,
    membersById: Map<String, MemberView>,
    photo: (MemberView) -> String?,
    ui: UiActions,
    lyrics: LyricsState,
    onPick: () -> Unit,
    onLikeAnchor: (Offset) -> Unit,
    onShowListeners: () -> Unit,
) {
    val pb = room.playback
    val item = pb.item
    var now by remember { mutableLongStateOf(controller.serverNowMs()) }
    LaunchedEffect(pb.seq, pb.status) {
        while (true) {
            now = controller.serverNowMs()
            delay(250)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 换歌时封面轻轻缩放淡入；正在放时比等待时略大一点，像唱片"立起来"。
        val playing = pb.status == PlaybackStatus.PLAYING
        val coverScale by animateFloatAsState(if (playing || item == null) 1f else 0.92f, spring(dampingRatio = 0.6f, stiffness = 200f), label = "cover")
        // 点封面切到歌词、点歌词切回封面，原地切换，标题和进度条不动（跟主流播放器一样）。
        var showLyrics by remember { mutableStateOf(false) }
        val tap = remember { MutableInteractionSource() }
        Crossfade(showLyrics && item != null, animationSpec = tween(280), label = "coverLyrics") { lyricsMode ->
            if (lyricsMode) {
                // 逐帧进度只在歌词可见时跑，看封面时不占帧
                LyricsPanel(
                    lyrics, rememberRoomPosition(controller, pb),
                    Modifier.fillMaxWidth().height(260.dp).clickable(interactionSource = tap, indication = null) { showLyrics = false },
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(260.dp)
                        .clickable(enabled = item != null, interactionSource = tap, indication = null) { showLyrics = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.graphicsLayer { scaleX = coverScale; scaleY = coverScale }.shadow(28.dp, RoundedCornerShape(22.dp))) {
                        AnimatedContent(
                            item?.track,
                            contentKey = { it?.key },
                            transitionSpec = {
                                (fadeIn(tween(380)) + scaleIn(tween(380), initialScale = 0.9f)) togetherWith fadeOut(tween(220))
                            },
                            label = "cover",
                        ) { t -> Cover(t, 260.dp, corner = 22.dp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        if (item == null) {
            Text("还没有在放歌", style = MaterialTheme.typography.titleLarge)
            Text("点一首歌，房间里的人会同时开始听", color = Palette.Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            GradientButton("去点歌", modifier = Modifier.width(180.dp), onClick = onPick)
            Spacer(Modifier.height(18.dp))
            ListenersRow(online, photo, onShowListeners)
            return@Column
        }

        Text(
            item.track.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Text(
            item.track.artistText, color = Palette.Muted, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            EqualizerBars(playing, Palette.Accent, size = 12.dp)
            membersById[item.requestedBy]?.let { m ->
                Spacer(Modifier.width(8.dp))
                Avatar(m.userId, m.nickname, m.avatar, 18.dp, photo(m))
                Text(" ${m.nickname} 点的", style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }

        Spacer(Modifier.height(22.dp))
        val duration = item.track.durationMs.coerceAtLeast(1)
        val startAt = pb.startAt
        val waiting = pb.status == PlaybackStatus.PREPARING || (startAt != null && now < startAt)
        if (waiting) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Palette.Accent, trackColor = Color.White.copy(alpha = 0.12f),
            )
            Text("等大家缓冲好就一起开始…", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp))
        } else {
            val position = if (startAt != null) (now - startAt).coerceIn(0, duration) else 0
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(position.toFloat() / duration).height(4.dp).clip(CircleShape).background(Palette.AccentGradient))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.weight(1f))
                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }

        val interrupted by controller.interrupted.collectAsState()
        AnimatedVisibility(interrupted, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            InterruptedBar(onResume = controller::resumePlayback)
        }
        Spacer(Modifier.height(14.dp))
        VolumeBar(controller, ui)
        QualityFallbackHint(controller)

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { ListenersRow(online, photo, onShowListeners) }
            val myLikes by controller.myLikes.collectAsState()
            val mine = myLikes.seq == pb.seq
            LikeButton(
                controller,
                likes = if (mine) maxOf(pb.likes, myLikes.total) else pb.likes,
                liked = mine || me in pb.likerIds,
                onAnchor = onLikeAnchor,
            )
            Spacer(Modifier.width(8.dp))
            val skipped = me in pb.skipperIds
            // 与服务端 SkipNeed 一致：两人及以上要两票，只有一人时一票。
            val need = online.size.coerceIn(1, 2)
            val press = remember { MutableInteractionSource() }
            Row(
                Modifier.pressScale(press, 0.9f).clip(CircleShape)
                    .background(if (skipped) Palette.AccentSoft else Color.White.copy(alpha = 0.08f))
                    .border(1.dp, if (skipped) Palette.Accent else Color.Transparent, CircleShape)
                    .pressClick(press) { ui.run { controller.skip(!skipped) } }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.SkipNext, contentDescription = null,
                    tint = if (skipped) Palette.Accent else Palette.Text, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("切歌 ", style = MaterialTheme.typography.labelSmall, color = if (skipped) Palette.Accent else Palette.Text)
                RollingCount(pb.skipperIds.size, if (skipped) Palette.Accent else Palette.Text)
                Text("/$need", style = MaterialTheme.typography.labelSmall, color = if (skipped) Palette.Accent else Palette.Text)
            }
        }
    }
}

/** 被别的 App 或拔耳机打断后的提示，点一下跳回房间进度接着放。 */
@Composable
internal fun InterruptedBar(onResume: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.padding(top = 14.dp).pressScale(press, 0.97f).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(Palette.AccentSoft).pressClick(press, onClick = onResume)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
        Text(
            "播放被打断了 · 点这里恢复", color = Palette.Text, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 点赞：可以连点，每点一次全房间都飘一簇大拇指，按钮自己也弹一下。 */
@Composable
internal fun LikeButton(controller: AppController, likes: Int, liked: Boolean, onAnchor: (Offset) -> Unit) {
    val bounce = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .onGloballyPositioned { c -> onAnchor(c.positionInRoot() + Offset(c.size.width / 2f, c.size.height / 2f)) }
            .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
            .clip(CircleShape)
            .background(if (liked) Palette.AccentSoft else Color.White.copy(alpha = 0.08f))
            .clickable {
                scope.launch {
                    bounce.snapTo(0.82f)
                    bounce.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 600f))
                }
                controller.like()
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (liked) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbUpOffAlt, contentDescription = "点赞",
            tint = if (liked) Palette.Accent else Palette.Text, modifier = Modifier.size(17.dp),
        )
        if (likes > 0) {
            Spacer(Modifier.width(5.dp))
            RollingCount(likes, if (liked) Palette.Accent else Palette.Text)
        }
    }
}

/** 本机音量和静音，只影响自己听到的声音。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeBar(controller: AppController, ui: UiActions) {
    val volume by controller.volume.collectAsState()
    val muted by controller.muted.collectAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable { controller.toggleMute() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    muted || volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
                    volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                contentDescription = if (muted) "取消静音" else "静音",
                tint = if (muted) Palette.Accent else Palette.Muted, modifier = Modifier.size(20.dp),
            )
        }
        val colors = SliderDefaults.colors(
            thumbColor = Color.White, activeTrackColor = Palette.Accent, inactiveTrackColor = Color.White.copy(alpha = 0.12f),
        )
        val interaction = remember { MutableInteractionSource() }
        // 默认滑块又粗又显眼，这里换成细轨道和小圆点，和进度条一个风格。
        Slider(
            value = if (muted) 0f else volume,
            onValueChange = controller::setVolume,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = colors,
            interactionSource = interaction,
            thumb = { Box(Modifier.size(14.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White)) },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state, colors = colors, modifier = Modifier.height(4.dp),
                    thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                )
            },
        )
        Spacer(Modifier.width(12.dp))
        QualityToggle(controller, ui)
    }
}

/**
 * 音质分段按钮 [320k | 无损]：高亮的那一段就是自己选的音质，不会读成"点了切到…"。
 * 只影响自己，选一次之后的歌都按这个来；切换时正在放的这首也马上换。
 */
@Composable
private fun QualityToggle(controller: AppController, ui: UiActions) {
    val lossless by controller.lossless.collectAsState()
    Row(
        Modifier.height(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QualitySegment("320k", selected = !lossless) { controller.setLossless(false) }
        QualitySegment("无损", selected = lossless) {
            controller.setLossless(true)
            ui.toast("已选无损：只对你自己生效，之后的歌也是；流量和缓冲会多一些")
        }
    }
}

@Composable
private fun QualitySegment(label: String, selected: Boolean, onSelect: () -> Unit) {
    val bg by animateColorAsState(if (selected) Palette.Accent else Color.Transparent, tween(180), label = "seg")
    val fg by animateColorAsState(if (selected) Color.White else Palette.Muted, tween(180), label = "segText")
    Box(
        Modifier.fillMaxHeight().clip(CircleShape).background(bg)
            .clickable(enabled = !selected, onClick = onSelect)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** 选了无损但这首拿不到无损（没有无损版本、平台不支持）时，如实说一句正在放 320k。 */
@Composable
private fun QualityFallbackHint(controller: AppController) {
    val wanted by controller.lossless.collectAsState()
    val actual by controller.playingQuality.collectAsState()
    AnimatedVisibility(
        wanted && actual == AudioQuality.STANDARD,
        enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
    ) {
        Text(
            "这首没有无损版本，正在放 320k",
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun ListenersRow(online: List<MemberView>, photo: (MemberView) -> String?, onClick: () -> Unit, verb: String = "听") {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(online, 28.dp, photo, max = 4, ring = Palette.Background)
        Text(
            if (online.size <= 1) "  只有你在$verb" else "  ${online.size} 人在$verb",
            style = MaterialTheme.typography.labelSmall, color = Palette.Muted,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SectionHeader(title: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("  $hint", style = MaterialTheme.typography.labelSmall, color = Palette.Muted, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
internal fun QueueRow(
    item: QueueItem,
    membersById: Map<String, MemberView>,
    photo: (MemberView) -> String?,
    me: String,
    locked: Boolean,
    controller: AppController,
    ui: UiActions,
    modifier: Modifier = Modifier,
) {
    val mine = item.requestedBy == me
    val voted = me in item.voterIds
    val requester = membersById[item.requestedBy]
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (locked) Palette.SurfaceHigh else Palette.Surface).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(item.track, 48.dp, corner = 10.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.track.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                if (requester != null) {
                    Avatar(requester.userId, requester.nickname, requester.avatar, 16.dp, photo(requester))
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    (requester?.nickname ?: "已离开") + " · " + item.track.artistText,
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (locked) {
            Icon(Icons.Rounded.Lock, contentDescription = "已锁定", tint = Palette.Muted, modifier = Modifier.size(18.dp).padding(end = 2.dp))
            return@Row
        }
        val press = remember { MutableInteractionSource() }
        Row(
            Modifier.pressScale(press, 0.9f).clip(CircleShape)
                .background(if (voted) Palette.AccentSoft else Palette.SurfaceHigh)
                .pressClick(press) { ui.run { controller.vote(item.itemId, !voted) } }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.VerticalAlignTop, contentDescription = "置顶投票", tint = if (voted) Palette.Accent else Palette.Text, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text("置顶 ", style = MaterialTheme.typography.labelSmall, color = if (voted) Palette.Accent else Palette.Text)
            RollingCount(item.voterIds.size, if (voted) Palette.Accent else Palette.Text)
        }
        if (mine) {
            Box(
                Modifier.padding(start = 6.dp).size(30.dp).clip(CircleShape).clickable { ui.run { controller.removeSong(item.itemId) } },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, contentDescription = "删除", tint = Palette.Muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
internal fun DebugPanel(controller: AppController) {
    val d by controller.debug.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface.copy(alpha = 0.92f)).padding(14.dp),
    ) {
        Text("同步调试", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        val lines = listOf(
            "误差 drift" to (d.driftMs?.let { (if (it > 0) "+" else "") + "$it ms" } ?: "—"),
            "往返延迟 rtt" to (d.rttMs?.let { "$it ms" } ?: "—"),
            "应在 / 实际" to if (d.expectedMs != null && d.actualMs != null) "${formatMs(d.expectedMs!!)} / ${formatMs(d.actualMs!!)}" else "—",
            "同步档位" to d.mode.title,
            "播放速度" to d.rate.toString(),
            "音源" to if (d.usingProxy) "服务器代理" else "直连 CDN",
            "跳转次数" to d.seeks.toString(),
            "状态" to d.status,
        )
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(k, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.weight(1f))
                Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** 数字变化时上下滚动：变多从下往上，变少从上往下。 */
@Composable
private fun RollingCount(value: Int, color: Color) {
    AnimatedContent(
        value,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
        },
        label = "count",
    ) { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = color) }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun formatMs(ms: Long): String = "${formatTime(ms)}.${(ms % 1000).toString().padStart(3, '0')}"
