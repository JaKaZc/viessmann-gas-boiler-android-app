package com.vigasboiler.app.ui.heating

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import com.vigasboiler.app.R
import com.vigasboiler.app.feature.Day
import com.vigasboiler.app.feature.DialGrab
import com.vigasboiler.app.feature.DialPart
import com.vigasboiler.app.feature.Schedule
import com.vigasboiler.app.feature.ScheduleDrag
import com.vigasboiler.app.feature.ScheduleEntry
import com.vigasboiler.app.feature.ScheduleNow
import com.vigasboiler.app.ui.common.prettify

/** Colour of a schedule mode; everything outside periods is the default (reduced) mode. */
@Composable
fun modeColor(mode: String): Color = when (mode) {
    "comfort" -> MaterialTheme.colorScheme.tertiary
    "reduced" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

/** Current local day and minute, ticking every 30 s so "now" markers move. */
@Composable
fun rememberNow(): Pair<Day, Int> {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(30_000)
            value = LocalDateTime.now()
        }
    }
    return Day.entries[now.dayOfWeek.value - 1] to (now.hour * 60 + now.minute)
}

/** "Now · Normal · until 20:30, then Reduced". */
@Composable
fun ScheduleNowRow(schedule: Schedule, defaultMode: String) {
    val (day, minute) = rememberNow()
    val status = ScheduleNow.status(schedule, defaultMode, day, minute)
    val color = modeColor(status.mode)
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sched_now, prettify(status.mode)), style = MaterialTheme.typography.titleMedium)
            val until = status.untilMinute?.let { Schedule.format(it) }
            if (until != null && status.nextMode != null) {
                val next = prettify(status.nextMode)
                Text(
                    when (status.untilDayOffset) {
                        0 -> stringResource(R.string.sched_until_today, until, next)
                        1 -> stringResource(R.string.sched_until_tomorrow, until, next)
                        else -> stringResource(R.string.sched_until_day, prettify(Day.entries[(day.ordinal + status.untilDayOffset) % 7].key), until, next)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Calendar-style week: seven vertical 0–24 h columns with rounded period blocks, today
 * highlighted with a live "now" line. Tapping a column calls [onDayClick].
 */
@Composable
fun WeekColumns(schedule: Schedule, onDayClick: ((Day) -> Unit)? = null) {
    val (today, minute) = rememberNow()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val nowColor = MaterialTheme.colorScheme.onSurface
    // Blocks grow in when the schedule appears or changes.
    val grow = remember { Animatable(0f) }
    LaunchedEffect(schedule) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    Row(Modifier.fillMaxWidth()) {
        // Hour axis.
        Column(Modifier.width(28.dp).padding(top = 28.dp).height(200.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Day.entries.forEach { d ->
            val isToday = d == today
            val colors = schedule.day(d).map { modeColor(it.mode) }
            Column(
                Modifier.weight(1f).clip(MaterialTheme.shapes.small)
                    .then(if (onDayClick != null) Modifier.clickable { onDayClick(d) } else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.height(24.dp).padding(horizontal = 2.dp).clip(CircleShape)
                        .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        prettify(d.key).take(2),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Canvas(Modifier.padding(top = 4.dp).height(200.dp).fillMaxWidth().padding(horizontal = 5.dp)) {
                    val w = size.width
                    val r = CornerRadius(w / 2.5f, w / 2.5f)
                    drawRoundRect(track, cornerRadius = r)
                    listOf(6, 12, 18).forEach { h ->
                        val y = size.height * h / 24f
                        drawLine(grid, Offset(w * 0.2f, y), Offset(w * 0.8f, y), strokeWidth = 2f)
                    }
                    schedule.day(d).forEachIndexed { i, e ->
                        val s = Schedule.minutesOf(e.start) ?: return@forEachIndexed
                        val en = Schedule.minutesOf(e.end) ?: return@forEachIndexed
                        if (en <= s) return@forEachIndexed
                        val top = size.height * s / 1440f
                        val full = size.height * (en - s) / 1440f
                        drawRoundRect(colors[i], Offset(0f, top), Size(w, full * grow.value), r)
                    }
                    if (isToday) {
                        val y = size.height * minute / 1440f
                        drawLine(nowColor, Offset(-4f, y), Offset(w + 4f, y), strokeWidth = 4f, cap = StrokeCap.Round)
                        drawCircle(nowColor, radius = 7f, center = Offset(-4f, y))
                    }
                }
            }
        }
    }
}

/**
 * 24 h ring for one day that is edited by touch: midnight at the top, periods as arcs with a
 * handle at each end, 3-hour ticks and a "now" dot when [showNow]. Dragging a handle changes that
 * time, dragging an arc moves the whole period (see [ScheduleDrag]); tapping opens the period
 * through [onTap]. [label] is drawn inside the ring and gets what the finger holds.
 */
@Composable
fun DayDial(
    entries: List<ScheduleEntry>,
    showNow: Boolean,
    resolution: Int,
    onChange: (List<ScheduleEntry>) -> Unit,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable BoxScope.(DialGrab?) -> Unit,
) {
    val (_, minute) = rememberNow()
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.onSurface
    val handleFill = MaterialTheme.colorScheme.surfaceContainerLowest
    val colors = entries.map { modeColor(it.mode) }
    // Once per day shown: restarting on every change would make the arcs flicker while dragging.
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { sweep.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    var grab by remember { mutableStateOf<DialGrab?>(null) }
    val lift by animateFloatAsState(if (grab != null) 1f else 0f, label = "lift")
    val latest by rememberUpdatedState(entries)
    val latestResolution by rememberUpdatedState(resolution)
    val change by rememberUpdatedState(onChange)
    val tap by rememberUpdatedState(onTap)
    val haptics = LocalHapticFeedback.current

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.fillMaxWidth().fillMaxHeight().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val min = minOf(size.width, size.height).toFloat()
                    val stroke = min * DIAL_STROKE
                    val radius = min / 2 - stroke
                    val reach = maxOf(stroke, HANDLE_REACH.toPx())
                    val fromCentre = (down.position - centre).getDistance()
                    if (fromCentre !in (radius - reach)..(radius + reach)) return@awaitEachGesture
                    val toleranceMinutes = HANDLE_REACH.toPx() / (2 * PI * radius) * 1440
                    val origin = latest
                    val step = latestResolution
                    val candidates = ScheduleDrag.hit(origin, minuteAt(down.position, centre), toleranceMinutes)
                    if (candidates.isEmpty()) return@awaitEachGesture
                    // Consumed from here on, so the screen does not scroll under the finger.
                    down.consume()
                    grab = candidates.first()
                    var held = candidates.first()
                    var last = minuteAt(down.position, centre)
                    var travelled = 0.0
                    var moved = false
                    while (true) {
                        val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            pointer.consume()
                            break
                        }
                        val now = minuteAt(pointer.position, centre)
                        travelled += ScheduleDrag.ringDelta(last, now)
                        last = now
                        if (!moved && (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            held = ScheduleDrag.pick(origin, candidates, if (travelled < 0) -step else step, step)
                            grab = held
                        }
                        pointer.consume()
                        if (moved) {
                            val next = ScheduleDrag.apply(origin, held, travelled.roundToInt(), step)
                            if (next.map { it.start to it.end } != latest.map { it.start to it.end }) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                change(next)
                            }
                        }
                    }
                    grab = null
                    if (!moved) tap(held.index)
                }
            },
        ) {
            val stroke = size.minDimension * DIAL_STROKE
            val radius = size.minDimension / 2 - stroke
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            fun onRing(minutes: Int): Offset {
                val a = Math.toRadians(minutes / 1440.0 * 360 - 90)
                return Offset(center.x + (cos(a) * radius).toFloat(), center.y + (sin(a) * radius).toFloat())
            }
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            entries.forEachIndexed { i, e ->
                val s = Schedule.minutesOf(e.start) ?: return@forEachIndexed
                val en = Schedule.minutesOf(e.end) ?: return@forEachIndexed
                if (en <= s) return@forEachIndexed
                val start = s / 1440f * 360f - 90f
                val length = (en - s) / 1440f * 360f
                drawArc(colors[i], start, length * sweep.value, false, topLeft, arcSize, style = Stroke(stroke))
            }
            // Ticks at 3, 9, 15 and 21 h; 0/6/12/18 are marked by their numbers.
            for (h in 3 until 24 step 6) {
                val a = Math.toRadians((h / 24.0) * 360 - 90)
                val outer = radius - stroke * 0.8f
                val inner = outer - stroke * 0.35f
                drawLine(
                    tick.copy(alpha = 0.6f),
                    Offset(center.x + (cos(a) * inner).toFloat(), center.y + (sin(a) * inner).toFloat()),
                    Offset(center.x + (cos(a) * outer).toFloat(), center.y + (sin(a) * outer).toFloat()),
                    strokeWidth = 3f, cap = StrokeCap.Round,
                )
            }
            if (showNow) drawCircle(nowColor, stroke * 0.2f, onRing(minute))
            // Handles on top: the held ones grow under the finger.
            entries.forEachIndexed { i, e ->
                val held = grab?.takeIf { it.index == i }
                listOf(DialPart.Start to e.start, DialPart.End to e.end).forEach { (part, time) ->
                    val at = Schedule.minutesOf(time) ?: return@forEach
                    val active = held != null && (held.part == part || held.part == DialPart.Body)
                    val r = stroke * (0.56f + if (active) 0.22f * lift else 0f)
                    drawCircle(handleFill.copy(alpha = sweep.value), r, onRing(at))
                    drawCircle(colors[i].copy(alpha = sweep.value), r, onRing(at), style = Stroke(stroke * 0.16f))
                }
            }
        }
        // Hour labels just inside the ring.
        Box(Modifier.fillMaxWidth(0.62f).aspectRatio(1f)) {
            Text("0", Modifier.align(Alignment.TopCenter), style = MaterialTheme.typography.labelSmall, color = tick)
            Text("6", Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelSmall, color = tick)
            Text("12", Modifier.align(Alignment.BottomCenter), style = MaterialTheme.typography.labelSmall, color = tick)
            Text("18", Modifier.align(Alignment.CenterStart), style = MaterialTheme.typography.labelSmall, color = tick)
        }
        Box(Modifier.fillMaxWidth(0.5f), contentAlignment = Alignment.Center) { label(grab) }
    }
}

/** Ring thickness as a share of the dial's size. */
private const val DIAL_STROKE = 0.11f

/** How far from a handle a finger still catches it. */
private val HANDLE_REACH = 26.dp

/** Minute of the day at a point: midnight at the top, clockwise. */
private fun minuteAt(position: Offset, centre: Offset): Double {
    val degrees = Math.toDegrees(atan2((position.y - centre.y).toDouble(), (position.x - centre.x).toDouble())) + 90
    return degrees.mod(360.0) / 360 * 1440
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Mode legend for the schedule views. */
@Composable
fun ModeLegend(modes: List<String>, defaultMode: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        modes.forEach { LegendDot(prettify(it), modeColor(it)) }
        defaultMode?.let { LegendDot(stringResource(R.string.sched_outside_periods, prettify(it)), MaterialTheme.colorScheme.surfaceContainerHighest) }
    }
}

/** Thin rounded period strip used in compact places. */
@Composable
fun DayBar(entries: List<ScheduleEntry>, modifier: Modifier = Modifier, height: Int = 10) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val colors = entries.map { modeColor(it.mode) }
    Canvas(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(height.dp))) {
        drawRect(track)
        entries.forEachIndexed { i, e ->
            val s = Schedule.minutesOf(e.start) ?: return@forEachIndexed
            val en = Schedule.minutesOf(e.end) ?: return@forEachIndexed
            if (en > s) drawRect(colors[i], Offset(size.width * s / 1440f, 0f), Size(size.width * (en - s) / 1440f, size.height))
        }
    }
}
