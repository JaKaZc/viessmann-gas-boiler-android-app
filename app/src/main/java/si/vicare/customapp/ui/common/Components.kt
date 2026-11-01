package si.vicare.customapp.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.round
import si.vicare.customapp.R
import si.vicare.customapp.feature.Constraints
import si.vicare.customapp.ui.PendingCommand
import si.vicare.customapp.ui.theme.AppColors
import si.vicare.customapp.ui.theme.SectionLabelStyle

/** "comfortEnergySaving" → "Comfort energy saving": readable labels for API enum values. */
fun prettify(token: String): String =
    token.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }

/** Small uppercase caption above a group of cards. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = SectionLabelStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
    if (onClick != null) Card(onClick, modifier.fillMaxWidth(), colors = colors, shape = MaterialTheme.shapes.medium, content = inner)
    else Card(modifier.fillMaxWidth(), colors = colors, shape = MaterialTheme.shapes.medium, content = inner)
}

/** A card with an optional title row (icon + title). */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
        }
        content()
    }
}

/** Icon in a tinted circle. */
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Int = 36) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size((size * 0.55).dp), tint = tint)
    }
}

/** Metric tile: icon badge, label, large value and optional supporting line. */
@Composable
fun ValueTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    warn: Boolean = false,
    @DrawableRes icon: Int? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val container = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow
    // fillMaxHeight: tiles in a Row(Modifier.height(IntrinsicSize.Min)) share the tallest height.
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) IconBadge(icon, if (warn) MaterialTheme.colorScheme.error else accent, size = 28)
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AnimatedValueText(value, MaterialTheme.typography.headlineMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Pill with an icon, e.g. "Burner on · 15 %". [pulse] makes the icon breathe (live activity). */
@Composable
fun StatusChip(text: String, @DrawableRes icon: Int?, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    val iconAlpha = rememberPulse(pulse)
    Surface(modifier.animateContentSize(), shape = CircleShape, color = color.copy(alpha = 0.14f), contentColor = color) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(painterResource(icon), null, Modifier.size(16.dp).graphicsLayer { alpha = iconAlpha })
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun LabeledRow(label: String, value: String, @DrawableRes icon: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

/** Label, value and a thin bar, e.g. API budget used. [fraction] is 0…1. */
@Composable
fun MeterRow(label: String, value: String, fraction: Float, color: Color = MaterialTheme.colorScheme.primary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "meter")
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
    }
}

/** Navigation row with icon, title, optional subtitle and chevron. */
@Composable
fun NavRow(title: String, @DrawableRes icon: Int, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 68.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

enum class BannerLevel { Error, Warning }

@Composable
fun Banner(level: BannerLevel, text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    val extra = AppColors.extra
    val (container, content) = when (level) {
        BannerLevel.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        BannerLevel.Warning -> extra.warningContainer to extra.onWarningContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel, color = content) }
        }
    }
}

/** − value + control that only produces values allowed by [constraints]. */
@Composable
fun Stepper(value: Double, constraints: Constraints, unit: String?, onChange: (Double) -> Unit, enabled: Boolean = true) {
    val step = constraints.stepping ?: 1.0
    fun snap(v: Double): Double {
        val base = constraints.min ?: 0.0
        val snapped = base + round((v - base) / step) * step
        val bounded = snapped.coerceIn(constraints.min ?: Double.NEGATIVE_INFINITY, constraints.max ?: Double.POSITIVE_INFINITY)
        return round(bounded * 1000) / 1000
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { onChange(snap(value - step)) }, enabled = enabled && value > (constraints.min ?: Double.NEGATIVE_INFINITY), modifier = Modifier.size(40.dp)) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            formatWithUnit(value, unit, maxDecimals = 2),
            Modifier.widthIn(min = 76.dp),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        FilledTonalIconButton(onClick = { onChange(snap(value + step)) }, enabled = enabled && value < (constraints.max ?: Double.POSITIVE_INFINITY), modifier = Modifier.size(40.dp)) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Shows old → new for every change before anything is sent. */
@Composable
fun ConfirmCommandDialog(pending: PendingCommand, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pending.changes.forEach { change ->
                    Column {
                        Text(change.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(change.old, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("→", style = MaterialTheme.typography.titleMedium)
                            Text(change.new, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(stringResource(R.string.confirm_cost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
