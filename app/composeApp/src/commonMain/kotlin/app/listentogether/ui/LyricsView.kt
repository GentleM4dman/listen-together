package app.listentogether.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import app.listentogether.AppController
import app.listentogether.lyrics.LyricLine
import app.listentogether.lyrics.Lyrics
import app.listentogether.model.PlaybackState
import app.listentogether.model.PlaybackStatus
import app.listentogether.model.TrackRef
import kotlinx.coroutines.delay

/** 歌词的加载状态：还在拉、没有歌词、有歌词。 */
sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Ready(val lyrics: Lyrics) : LyricsState
}

/** 拉当前歌的歌词，失败隔几秒重试几次（服务器偶尔解析慢），最后放弃当没有。 */
@Composable
fun rememberLyrics(controller: AppController, track: TrackRef?): State<LyricsState> =
    produceState<LyricsState>(LyricsState.Loading, track?.key) {
        value = LyricsState.Loading
        if (track == null) { value = LyricsState.None; return@produceState }
        repeat(3) { attempt ->
            val r = runCatching { controller.lyrics(track) }
            r.onSuccess { value = it?.let(LyricsState::Ready) ?: LyricsState.None; return@produceState }
            delay(3_000L * (attempt + 1))
        }
        value = LyricsState.None
    }

/**
 * 房间当前进度（毫秒），每帧更新一次。只在 draw 阶段读它的地方会重绘，不会整块重组；
 * 没在放（等缓冲、空房间）时停在 0，不跑帧循环。
 */
@Composable
fun rememberRoomPosition(controller: AppController, pb: PlaybackState): State<Long> {
    val pos = remember { mutableLongStateOf(0L) }
    val startAt = pb.startAt
    LaunchedEffect(pb.seq, pb.status, startAt) {
        if (startAt == null || pb.status != PlaybackStatus.PLAYING) { pos.longValue = 0; return@LaunchedEffect }
        while (true) {
            withFrameMillis { }
            pos.longValue = (controller.serverNowMs() - startAt).coerceAtLeast(0)
        }
    }
    return pos
}

/**
 * 封面位置的歌词：当前句居中、放大、逐句填色，自动滚动；上下边缘渐隐。
 * 手动拖动后 3 秒内不抢滚动。点击由外面的 modifier 处理（切回封面）。
 */
@Composable
fun LyricsPanel(state: LyricsState, position: State<Long>, modifier: Modifier) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is LyricsState.Ready -> LyricsList(state.lyrics, position)
            LyricsState.Loading -> Text("歌词加载中…", color = Palette.Muted)
            LyricsState.None -> Text("这首歌暂无歌词", color = Palette.Muted)
        }
    }
}

@Composable
private fun LyricsList(lyrics: Lyrics, position: State<Long>) {
    val index by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val padPx = with(density) { LIST_PAD.roundToPx() }
    val wavePx = with(density) { 7.dp.toPx() }
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var resumeTick by remember { mutableLongStateOf(0L) }
    var prevIndex by remember(lyrics) { mutableIntStateOf(index) }
    var browsing by remember { mutableStateOf(false) }

    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop) {
                userScrollAt = currentMillis()
                browsing = true
            }
        }
    }
    // 手动拖完 3 秒后自动回到当前句，恢复景深模糊
    LaunchedEffect(userScrollAt) {
        if (userScrollAt != 0L) {
            delay(3_000)
            browsing = false
            resumeTick = currentMillis()
        }
    }
    LaunchedEffect(index, resumeTick, lyrics) {
        if (userScrollAt != 0L && currentMillis() - userScrollAt < 3_000) return@LaunchedEffect
        val target = index.coerceAtLeast(0)
        // 等一帧让新激活行的 AmKaraokeLine 完成测量，避免切换瞬间高度变化导致跳动
        withFrameMillis { }
        val info = list.layoutInfo
        val focusY = (info.viewportSize.height * 0.32f).toInt()
        val visible = info.visibleItemsInfo.firstOrNull { it.index == target }
        val jump = abs(target - prevIndex) > 4
        prevIndex = target
        if (visible != null && !jump && focusY > 0) {
            val targetOffset = info.viewportStartOffset + focusY
            val delta = (visible.offset - targetOffset).toFloat()
            if (abs(delta) > 1f) {
                // 对齐 aura-music LINE_SPRING (stiffness=100, damping=18 -> dampingRatio≈0.90)：从容柔和跟进
                list.animateScrollBy(
                    delta,
                    spring(dampingRatio = 0.90f, stiffness = 95f),
                )
            }
        } else {
            list.animateScrollToItem(target, scrollOffset = padPx - focusY)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(vertical = LIST_PAD, horizontal = 8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val current = i == index
            val dist = abs(i - index)
            val targetAlpha = when {
                current -> 1f
                browsing -> 0.78f
                dist == 1 -> 0.56f
                dist == 2 -> 0.36f
                else -> 0.22f
            }
            val targetBlur = when {
                current || browsing -> 0.dp
                dist == 1 -> 1.4.dp
                dist == 2 -> 2.8.dp
                else -> 4.2.dp
            }
            val alpha by animateFloatAsState(targetAlpha, tween(280), label = "a")
            val scale by animateFloatAsState(
                if (current) 1.07f else if (dist == 1) 0.97f else 0.94f,
                spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow),
                label = "s",
            )
            val blur by animateDpAsState(targetBlur, tween(280), label = "b")

            // aura-music LyricsMotion 尾随波浪：当前句之后按 rank*70ms (0..280) 递增延迟跟进
            val ripple = remember { Animatable(0f) }
            LaunchedEffect(index) {
                if (!browsing && index >= 0 && i > index && i - index <= 5) {
                    val rank = i - index
                    ripple.snapTo(wavePx * (0.4f + rank * 0.12f))
                    delay((rank * 70L).coerceIn(0L, 280L))
                    ripple.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 140f))
                } else if (ripple.value != 0f) {
                    ripple.animateTo(0f, tween(180))
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 9.dp, bottom = 9.dp)
                    .graphicsLayer {
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = scale
                        scaleY = scale
                        translationY = ripple.value
                    }
                    .let { if (blur > 0.2.dp) it.blur(blur) else it },
                horizontalAlignment = Alignment.Start,
            ) {
                val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
                when {
                    line.text.isEmpty() -> Text("♪", style = style, color = Palette.Muted)
                    current && line.words.isNotEmpty() -> AmKaraokeLine(
                        line = line,
                        nextStartMs = lyrics.lines.getOrNull(i + 1)?.startMs,
                        position = position,
                        style = style,
                    )
                    current -> Text(line.text, style = style, color = Color.White)
                    else -> Text(line.text, style = style, color = Palette.Text)
                }
                line.translation?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, textAlign = TextAlign.Start,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// aura-music (dingyi222666/aura-music) 逐字/逐词动画数学移植：
//   springSystem.ts 闭式弹簧解算器 + liftAt
//   LyricLine.ts    cubic-bezier 曲线、emphasisShape、shouldEmphasizeWord、
//                   getEmphasisProfile、isTrailingWord
//   LyricsMotion.ts rank*70ms (clamp 0..280) 尾随波浪（在 LyricsList 里）
// ---------------------------------------------------------------------------

private const val EMPHASIS_ENTRY_LEAD_MS = 400L
private const val EMPHASIS_MIN_DURATION_MS = 1500L
private const val EMPHASIS_MAX_CHARS = 7
private const val EMPHASIS_SCALE = 0.10f
private const val EMPHASIS_TRAIL = 1.2f

// LIFT_SPRING = { mass: 1, stiffness: 14, damping: 7, precision: 0.001 }
private const val LIFT_MASS = 1f
private const val LIFT_STIFFNESS = 14f
private const val LIFT_DAMPING = 7f
private const val LIFT_PRECISION = 0.001f

/**
 * springSystem.ts 的 advance：m*x'' + c*x' + k*(x-target) = 0 闭式解，
 * 欠阻尼 / 临界阻尼 / 过阻尼三分支。返回 (current, velocity)；已稳定时为 null。
 */
private fun springAdvance(
    current: Float,
    target: Float,
    velocity: Float,
    mass: Float,
    stiffness: Float,
    damping: Float,
    precision: Float,
    dt: Float,
): Pair<Float, Float>? {
    if (dt <= 0f) return null
    if (current == target && velocity == 0f) return null
    val a = damping / (2f * mass)
    val k = stiffness / mass
    val d = a * a - k
    val x = current - target
    val v = velocity
    val position: Float
    val newVelocity: Float
    if (abs(d) < 1e-8f * maxOf(1f, k)) {
        val decay = exp(-a * dt)
        val b = v + a * x
        position = decay * (x + b * dt)
        newVelocity = decay * (v - a * b * dt)
    } else if (d < 0f) {
        val w = sqrt(-d)
        val decay = exp(-a * dt)
        val c = cos(w * dt)
        val s = sin(w * dt) / w
        position = decay * (x * c + (v + a * x) * s)
        newVelocity = decay * (v * c - (a * v + k * x) * s)
    } else {
        val w = sqrt(d)
        val slow = -k / (a + w)
        val fast = -a - w
        val b = (v - fast * x) / (slow - fast)
        val c = x - b
        position = b * exp(slow * dt) + c * exp(fast * dt)
        newVelocity = slow * b * exp(slow * dt) + fast * c * exp(fast * dt)
    }
    val settled = abs(position) <= precision && abs(newVelocity) <= precision
    return if (settled) null else (target + position) to newVelocity
}

/** liftAt(elapsedSec)：从 (0 -> 1, v=0) 解 LIFT_SPRING，clamp 到 [0,1]。 */
private fun liftAt(elapsedSec: Float): Float {
    if (elapsedSec <= 0f) return 0f
    val (cur, _) = springAdvance(
        0f, 1f, 0f,
        LIFT_MASS, LIFT_STIFFNESS, LIFT_DAMPING, LIFT_PRECISION,
        elapsedSec,
    ) ?: return 1f
    return cur.coerceIn(0f, 1f)
}

// LyricLine.ts 的 makeCurve / 三次贝塞尔 y(x) 求值
private fun cubicA(p1: Float, p2: Float) = 1f - 3f * p2 + 3f * p1
private fun cubicB(p1: Float, p2: Float) = 3f * p2 - 6f * p1
private fun cubicC(p1: Float) = 3f * p1

private fun sampleCurve(t: Float, p1: Float, p2: Float) =
    ((cubicA(p1, p2) * t + cubicB(p1, p2)) * t + cubicC(p1)) * t

private fun sampleSlope(t: Float, p1: Float, p2: Float) =
    3f * cubicA(p1, p2) * t * t + 2f * cubicB(p1, p2) * t + cubicC(p1)

private fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): (Float) -> Float = { value ->
    if (value <= 0f || value >= 1f) {
        value.coerceIn(0f, 1f)
    } else {
        var t = value
        for (i in 0 until 8) {
            val slope = sampleSlope(t, x1, x2)
            if (abs(slope) < 1e-6f) break
            val delta = sampleCurve(t, x1, x2) - value
            t -= delta / slope
        }
        t = t.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        for (i in 0 until 10) {
            val cur = sampleCurve(t, x1, x2)
            if (abs(cur - value) < 1e-6f) break
            if (cur > value) high = t else low = t
            t = (low + high) * 0.5f
        }
        sampleCurve(t, y1, y2).coerceIn(0f, 1f)
    }
}

private val curveRise = cubicBezier(0.2f, 0.4f, 0.58f, 1.0f)
private val curveFall = cubicBezier(0.3f, 0.0f, 0.58f, 1.0f)

/** emphasisShape：前 50% 用 curveRise 升起，后 50% 用 curveFall 回落。 */
private fun emphasisShape(t: Float): Float =
    if (t < 0.5f) curveRise(t / 0.5f) else 1f - curveFall((t - 0.5f) / 0.5f)

private fun isCjkToken(text: String): Boolean = text.any {
    it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF || it.code in 0xAC00..0xD7AF
}

private data class LyricToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isVerbatim: Boolean,
    val isTrailing: Boolean,
)

/**
 * shouldEmphasizeWord：逐字时间戳 + 持续 >=1.5s + <=7 个字符；
 * 多字 CJK token 不强调（isCjk && charCount > 1）。
 */
private fun shouldEmphasizeToken(token: LyricToken): Boolean {
    if (!token.isVerbatim) return false
    val text = token.text.trim()
    if (text.isEmpty()) return false
    if (token.endMs - token.startMs < EMPHASIS_MIN_DURATION_MS) return false
    val charCount = text.length
    if (isCjkToken(text) && charCount > 1) return false
    return (charCount > 1 || isCjkToken(text)) && charCount <= EMPHASIS_MAX_CHARS
}

/**
 * getEmphasisProfile 的 (zoom, span)：
 * zoom = (spanSec/2) 大于 1 开方、小于 1 立方，*0.6；行尾词 *1.6，封顶 1.2；
 * span 行尾词 *EMPHASIS_TRAIL。单位毫秒。
 */
private fun emphasisProfile(token: LyricToken): Pair<Float, Long>? {
    if (!shouldEmphasizeToken(token)) return null
    var spanMs = maxOf(EMPHASIS_MIN_DURATION_MS, token.endMs - token.startMs)
    val spanSec = spanMs / 1000f
    var zoom = (spanSec / 2f).let { if (it > 1f) sqrt(it) else it.pow(3) } * 0.6f
    if (token.isTrailing) {
        zoom *= 1.6f
        spanMs = (spanMs * EMPHASIS_TRAIL).toLong()
    }
    return minOf(1.2f, zoom) to spanMs
}

private fun buildLyricTokens(line: LyricLine, nextStartMs: Long?): List<LyricToken> {
    val text = line.text
    if (text.isEmpty()) return emptyList()
    if (line.words.isNotEmpty()) {
        val w = line.words
        val fallbackEnd = line.endMs ?: nextStartMs ?: (w.last().first + 800L)
        val tokens = w.mapIndexed { idx, (start, charStart) ->
            val nextPair = w.getOrNull(idx + 1)
            val charEnd = (nextPair?.second ?: text.length).coerceIn(charStart, text.length)
            val end = (nextPair?.first ?: fallbackEnd).coerceAtLeast(start + 1L)
            LyricToken(text.substring(charStart, charEnd), start, end, isVerbatim = true, isTrailing = false)
        }.filter { it.text.isNotEmpty() }
        return markTrailing(tokens)
    }

    // 无逐字时间戳时，按英文单词 / 中文单字拆分并按音节权重分配非线性时段
    val parts = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3040..0x30FF || ch.code in 0xAC00..0xD7AF) {
            parts += ch.toString()
            i++
        } else if (ch == ' ') {
            if (parts.isNotEmpty()) parts[parts.lastIndex] = parts.last() + " "
            i++
        } else {
            val start = i
            while (i < text.length && text[i] != ' ' &&
                text[i].code !in 0x4E00..0x9FFF &&
                text[i].code !in 0x3040..0x30FF &&
                text[i].code !in 0xAC00..0xD7AF
            ) {
                i++
            }
            if (i < text.length && text[i] == ' ') i++
            parts += text.substring(start, i)
        }
    }
    if (parts.isEmpty()) return emptyList()
    val estimate = 600L + text.length * 260L
    val span = (line.endMs?.takeIf { it > line.startMs }?.minus(line.startMs)
        ?: nextStartMs?.let { minOf(it - line.startMs, estimate) }
        ?: estimate).coerceAtLeast(parts.size * 60L)
    val weights = parts.map { p -> p.trim().length.coerceAtLeast(1) + 1 }
    val totalWeight = weights.sum().coerceAtLeast(1)
    var cursor = line.startMs
    val tokens = parts.mapIndexed { idx, part ->
        val dur = if (idx == parts.lastIndex) (line.startMs + span - cursor).coerceAtLeast(40L)
        else (span * weights[idx] / totalWeight).coerceAtLeast(40L)
        val token = LyricToken(part, cursor, cursor + dur, isVerbatim = false, isTrailing = false)
        cursor += dur
        token
    }
    return markTrailing(tokens)
}

/** isTrailingWord：整条歌词里最后一个非空白 token。 */
private fun markTrailing(tokens: List<LyricToken>): List<LyricToken> {
    var last = tokens.indexOfLast { it.text.isNotBlank() }
    if (last < 0) last = tokens.lastIndex
    return tokens.mapIndexed { i, t -> if (i == last) t.copy(isTrailing = true) else t }
}

/**
 * Apple Music 风格逐字/逐词歌词行（aura-music LyricLine.ts 移植）：
 * - 基础上浮：LIFT_SPRING 闭式解 liftAt，随演唱时间平滑升起并保持；
 * - 长词强调：emphasisShape（rise/fall 两段 cubic-bezier）驱动的持续 zoom + rise；
 * - 扫光：curveRise 缓动的非线性水平擦除，软羽化渐变。
 * 进度只在 graphicsLayer / drawWithContent 中读取，60fps 下零重组开销。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmKaraokeLine(
    line: LyricLine,
    nextStartMs: Long?,
    position: State<Long>,
    style: TextStyle,
) {
    val tokens = remember(line, nextStartMs) { buildLyricTokens(line, nextStartMs) }
    val emphasis = remember(tokens) { tokens.map { emphasisProfile(it) } }
    val liftPx = with(LocalDensity.current) { 3.dp.toPx() }
    val dimColor = Palette.Text.copy(alpha = 0.40f)

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tokens.forEachIndexed { index, token ->
            var layout by remember(token) { mutableStateOf<TextLayoutResult?>(null) }
            val profile = emphasis[index]
            Text(
                token.text,
                style = style,
                color = Color.White,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        val pos = position.value
                        val elapsedSec = (pos - token.startMs) / 1000f
                        // LIFT_SPRING：词开始演唱时平滑升起并稳定保持
                        val lift = liftAt(elapsedSec)
                        var scale = 1f + 0.025f * lift
                        var ty = -liftPx * 0.65f * lift
                        // 长词强调：[start-400ms, start+spanMs] 上的持续 zoom + rise
                        if (profile != null) {
                            val (zoom, spanMs) = profile
                            val t = ((pos - (token.startMs - EMPHASIS_ENTRY_LEAD_MS)).toFloat() /
                                (spanMs + EMPHASIS_ENTRY_LEAD_MS).toFloat()).coerceIn(0f, 1f)
                            val shape = emphasisShape(t)
                            scale += EMPHASIS_SCALE * zoom * shape
                            ty += -liftPx * 1.4f * shape
                        }
                        scaleX = scale
                        scaleY = scale
                        translationY = ty
                    }
                    .drawWithContent {
                        drawContent()
                        val l = layout ?: return@drawWithContent
                        val pos = position.value
                        val raw = ((pos - token.startMs).toFloat() /
                            (token.endMs - token.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                        val feather = 18f
                        val x = when {
                            raw >= 1f -> size.width + feather
                            raw <= 0f -> -feather
                            else -> raw * size.width
                        }
                        val fadeStart = (x - feather).coerceIn(0f, size.width)
                        val fadeEnd = (x + feather).coerceIn(0f, size.width)
                        if (fadeStart > 0f) {
                            drawRect(Color.White, topLeft = Offset.Zero, size = Size(fadeStart, size.height), blendMode = BlendMode.SrcIn)
                        }
                        if (fadeEnd > fadeStart) {
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.White,
                                    0.5f to Color(0xFFFFD8DE),
                                    1f to dimColor,
                                    startX = fadeStart,
                                    endX = fadeEnd,
                                ),
                                topLeft = Offset(fadeStart, 0f),
                                size = Size(fadeEnd - fadeStart, size.height),
                                blendMode = BlendMode.SrcIn,
                            )
                        }
                        if (fadeEnd < size.width) {
                            drawRect(dimColor, topLeft = Offset(fadeEnd, 0f), size = Size(size.width - fadeEnd, size.height), blendMode = BlendMode.SrcIn)
                        }
                    },
            )
        }
    }
}

/**
 * 卡拉 OK 式填色：保留给其他调用方使用的整行版本。
 */
@Composable
fun KaraokeText(text: String, progress: () -> Float, style: TextStyle, maxLines: Int = Int.MAX_VALUE) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val activeColor = Color.White
    val dimColor = Palette.Text.copy(alpha = 0.42f)
    Text(
        text, style = style, color = Color.White, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                val chars = progress().coerceIn(0f, 1f) * text.length
                val cut = chars.toInt().coerceAtMost(text.length)
                val feather = 20f
                for (ln in 0 until l.lineCount) {
                    val start = l.getLineStart(ln)
                    val end = l.getLineEnd(ln, visibleEnd = true)
                    val x = when {
                        chars >= end -> size.width + feather
                        chars <= start -> -feather
                        else -> {
                            val a = l.getHorizontalPosition(cut, usePrimaryDirection = true)
                            val b = if (cut + 1 < end) l.getHorizontalPosition(cut + 1, usePrimaryDirection = true) else l.getLineRight(ln)
                            a + (b - a) * (chars - cut)
                        }
                    }
                    val top = l.getLineTop(ln)
                    val h = l.getLineBottom(ln) - top
                    val fadeStart = (x - feather).coerceIn(0f, size.width)
                    val fadeEnd = (x + feather).coerceIn(0f, size.width)
                    if (fadeStart > 0f) {
                        drawRect(Palette.AccentGradient, topLeft = Offset(0f, top), size = Size(fadeStart, h), blendMode = BlendMode.SrcIn)
                    }
                    if (fadeEnd > fadeStart) {
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Palette.Accent,
                                0.55f to activeColor,
                                1f to dimColor,
                                startX = fadeStart,
                                endX = fadeEnd,
                            ),
                            topLeft = Offset(fadeStart, top),
                            size = Size(fadeEnd - fadeStart, h),
                            blendMode = BlendMode.SrcIn,
                        )
                    }
                    if (fadeEnd < size.width) {
                        drawRect(dimColor, topLeft = Offset(fadeEnd, top), size = Size(size.width - fadeEnd, h), blendMode = BlendMode.SrcIn)
                    }
                }
            },
    )
}

private val LIST_PAD = 120.dp

private val since = kotlin.time.TimeSource.Monotonic.markNow()

// 从 1 开始，0 留给"没拖过"
private fun currentMillis(): Long = since.elapsedNow().inWholeMilliseconds + 1
