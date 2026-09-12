package com.leohearts.alternativeunlockhook.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class CardPosition { Leading, Center, Trailing, Solo }

val GroupedListSpacing: Dp = 2.dp

data class CornerRole(
    val topStart: Boolean = false,
    val topEnd: Boolean = false,
    val bottomStart: Boolean = false,
    val bottomEnd: Boolean = false,
) {
    companion object {
        val None = CornerRole()
        val All = CornerRole(topStart = true, topEnd = true, bottomStart = true, bottomEnd = true)
    }
}

fun CornerRole.toShape(outer: Dp = 16.dp, inner: Dp = 6.dp): RoundedCornerShape =
    RoundedCornerShape(
        topStart = if (topStart) outer else inner,
        topEnd = if (topEnd) outer else inner,
        bottomStart = if (bottomStart) outer else inner,
        bottomEnd = if (bottomEnd) outer else inner,
    )

inline fun Modifier.thenIf(condition: Boolean, factory: Modifier.() -> Modifier): Modifier =
    if (condition) factory() else this

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GroupedRow(
    modifier: Modifier = Modifier,
    position: CardPosition = CardPosition.Solo,
    cornerRole: CornerRole? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    elevation: Dp = 10.dp,
    hideExtras: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    verticalPadding: Dp = 14.dp,
    horizontalPadding: Dp = 14.dp,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    tooltip: String = "",
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animatedOuter by animateDpAsState(
        targetValue = if (isPressed) 16.dp else 6.dp,
        label = "groupedRowOuterCorner",
    )
    val shape = (cornerRole ?: when (position) {
        CardPosition.Leading -> CornerRole(topStart = true, topEnd = true)
        CardPosition.Center -> CornerRole.None
        CardPosition.Trailing -> CornerRole(bottomStart = true, bottomEnd = true)
        CardPosition.Solo -> CornerRole.All
    }).toShape(inner = animatedOuter)

    val background = if (selected) {
        MaterialTheme.colorScheme.surfaceColorAtElevation(elevation * 4)
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(elevation)
    }
    val selectionBorderColor by animateColorAsState(
        targetValue = if (selected && !hideExtras) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "selectionBorder"
    )
    val row = @Composable {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .alpha(if (enabled) 1f else 0.6f)
                    .background(background, shape)
                    .border(2.dp, selectionBorderColor, shape)
                    .thenIf(enabled && (onClick != null || onLongClick != null)) {
                        combinedClickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = { onClick?.invoke() },
                            onLongClick = { onLongClick?.invoke() },
                        )
                    }
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding,
                    ),
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
            if (!hideExtras) {
                AnimatedVisibility(
                    visible = selected,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    Box(
                        Modifier
                            .padding(6.dp)
                            .size(22.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
    Box(modifier) {
        if (tooltip.isNotEmpty()) {
            val tooltipState = rememberTooltipState()
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text(tooltip) } },
                state = tooltipState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                row()
            }
        } else {
            row()
        }
    }
}
