package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAppleColors

/**
 * Ambient atmospheric canvas behind frosted glass cards so translucent surfaces have depth.
 */
@Composable
fun AppleGlassBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val apple = LocalAppleColors.current
    val baseBg = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBg)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(apple.ambientTop, Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.10f),
                        radius = size.maxDimension * 0.65f
                    ),
                    radius = size.maxDimension * 0.65f,
                    center = Offset(size.width * 0.85f, size.height * 0.10f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(apple.ambientBottom, Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.82f),
                        radius = size.maxDimension * 0.60f
                    ),
                    radius = size.maxDimension * 0.60f,
                    center = Offset(size.width * 0.12f, size.height * 0.82f)
                )
            }
    ) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val apple = LocalAppleColors.current
    val fill = if (elevated) apple.glassFillElevated else apple.glassFill
    val specularBorder = Brush.linearGradient(
        colors = listOf(apple.glassHighlight, apple.glassEdge)
    )
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = fill,
        tonalElevation = 0.dp,
        shadowElevation = if (apple.isDark) 0.dp else 6.dp,
        border = BorderStroke(1.dp, specularBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
fun InsetGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier, content = content)
}

@Composable
fun GlassMetricTile(
    value: String,
    label: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    monospaceValue: Boolean = false
) {
    val apple = LocalAppleColors.current
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Text(
                text = value,
                style = if (monospaceValue) {
                    MaterialTheme.typography.displayLarge.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    MaterialTheme.typography.displayLarge.copy(
                        fontFamily = InterFontFamily,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = apple.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun AppleSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 0.9.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = LocalAppleColors.current.subtleText,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 6.dp)
    )
}

@Composable
fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 18.dp),
        thickness = 0.5.dp,
        color = LocalAppleColors.current.hairline.copy(alpha = 0.45f)
    )
}

@Composable
fun AppleSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingText: String? = null,
    monospaceTrailing: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(horizontal = 18.dp, vertical = 14.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalAppleColors.current.subtleText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailingText != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = trailingText,
                style = if (monospaceTrailing) {
                    MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp)
                } else {
                    MaterialTheme.typography.labelLarge
                },
                color = LocalAppleColors.current.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (trailingContent != null) {
            Spacer(Modifier.width(10.dp))
            trailingContent()
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
fun <T> AppleSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier
) {
    val apple = LocalAppleColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = apple.pillSurface,
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(apple.glassHighlight, apple.glassEdge)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val bg by animateColorAsState(
                    targetValue = if (isSelected) apple.glassFillElevated else Color.Transparent,
                    animationSpec = spring(),
                    label = "seg_bg"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 42.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .clickable { onSelect(value) }
                        .testTag("${tagPrefix}_${label.lowercase().replace(" ", "_")}")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else apple.subtleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
