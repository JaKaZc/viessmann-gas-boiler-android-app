package com.vigasboiler.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vigasboiler.app.R

/** One bottom-bar destination. */
class NavItem(val label: String, val icon: TabIconKind, val accent: Color, val badge: Int = 0)

/**
 * Bottom bar: a pill in the selected tab's colour slides between tabs, icons animate on
 * selection (see [TabIcon]) and the label tint follows. Messages show a badge with the count.
 */
@Composable
fun AppNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().height(74.dp)) {
            val itemWidth = maxWidth / items.size
            val pillX by animateDpAsState(itemWidth * selected + (itemWidth - PILL_WIDTH) / 2, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "pill")
            val pillColor by animateColorAsState(items[selected].accent.copy(alpha = 0.16f), tween(300), label = "pillColor")
            Box(Modifier.offset(x = pillX, y = 10.dp).size(PILL_WIDTH, 34.dp).clip(CircleShape).background(pillColor))
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, item ->
                    val isSelected = i == selected
                    val tint by animateColorAsState(if (isSelected) item.accent else MaterialTheme.colorScheme.onSurfaceVariant, tween(250), label = "tint")
                    Column(
                        Modifier.weight(1f).fillMaxSize()
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                            .semantics { contentDescription = item.label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.size(PILL_WIDTH, 34.dp), contentAlignment = Alignment.Center) {
                            TabIcon(item.icon, isSelected, tint)
                            Badge(item.badge, Modifier.align(Alignment.TopCenter).offset(x = 13.dp, y = 2.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = tint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private val PILL_WIDTH = 60.dp

@Composable
private fun Badge(count: Int, modifier: Modifier) {
    AnimatedVisibility(count > 0, modifier, enter = scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
        Box(
            Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (count > 9) "9+" else "$count", color = MaterialTheme.colorScheme.onError, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Fixed screen header (it never collapses or moves with the content): a large title on tab
 * roots with a context line under it, a back arrow and a smaller title on detail screens.
 */
@Composable
fun AppHeader(title: String, subtitle: String?, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack != null) 4.dp else 20.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            val back = stringResource(R.string.back)
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = back }) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = null)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = if (onBack == null) MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "subtitle") { text ->
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Soft wash of the current tab's colour behind the top of the screen. */
@Composable
fun AccentBackdrop(accent: Color, content: @Composable () -> Unit) {
    val color by animateColorAsState(accent, tween(450), label = "wash")
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth().height(340.dp).background(
                Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), color.copy(alpha = 0.05f), Color.Transparent)),
            ),
        )
        content()
    }
}
