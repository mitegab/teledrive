package com.teledrive.playback.ui.amplify

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Visual language for the Amplify-style audio player.
 *
 * The reference design is a dark, glassy, purple-tinted music workspace, so the player
 * intentionally runs on its own palette instead of the app-wide Material colors. Keeping
 * the tokens in one object means the sidebar, playlist and timeline cannot drift apart.
 */
object AmplifyColors {
    val BackgroundTop = Color(0xFF261836)
    val BackgroundMid = Color(0xFF150D20)
    val BackgroundBottom = Color(0xFF090610)

    val Panel = Color(0x14FFFFFF)
    val PanelStrong = Color(0x22FFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val HairlineStrong = Color(0x33FFFFFF)

    val Accent = Color(0xFFC9B4FF)
    val AccentDeep = Color(0xFF8E6BFF)
    val AccentSoft = Color(0x33C9B4FF)
    val Blush = Color(0xFFFF9ED2)

    val TextPrimary = Color(0xFFF6F1FF)
    val TextSecondary = Color(0xB8F6F1FF)
    val TextMuted = Color(0x80F6F1FF)

    val LavenderBloom = Color(0x2EC9B4FF)
    val Danger = Color(0xFFFF8C9C)
    val Menu = Color(0xFF1E1529)
    val Scrim = Color(0xE6050308)
}

object AmplifyType {
    val eyebrow = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.SemiBold,
        color = AmplifyColors.TextMuted,
    )
    val brand = TextStyle(
        fontSize = 15.sp,
        letterSpacing = 4.sp,
        fontWeight = FontWeight.Medium,
        color = AmplifyColors.TextPrimary,
    )
    val display = TextStyle(
        fontSize = 32.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = (-0.4).sp,
        color = AmplifyColors.TextPrimary,
    )
    val sectionTitle = TextStyle(
        fontSize = 20.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        color = AmplifyColors.TextPrimary,
    )
    val trackTitle = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        color = AmplifyColors.TextPrimary,
    )
    val label = TextStyle(
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        color = AmplifyColors.TextSecondary,
    )
    val body = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, color = AmplifyColors.TextSecondary)
    val meta = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, color = AmplifyColors.TextMuted)
    val numeric = TextStyle(
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp,
        color = AmplifyColors.TextMuted,
    )
}

val AmplifyPanelShape = RoundedCornerShape(22.dp)
val AmplifyCardShape = RoundedCornerShape(16.dp)
val AmplifyRowShape = RoundedCornerShape(12.dp)

fun Modifier.amplifyClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    if (enabled) clickable(onClick = onClick) else this

/** Root backdrop: deep plum gradient with a light bloom in the top-right, like the reference art. */
@Composable
fun AmplifyBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.background(
            Brush.linearGradient(
                colors = listOf(
                    AmplifyColors.BackgroundTop,
                    AmplifyColors.BackgroundMid,
                    AmplifyColors.BackgroundBottom,
                ),
            )
        )
    ) {
        Spacer(
            Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.42f)
                .align(Alignment.TopEnd)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AmplifyColors.LavenderBloom,
                            Color.Transparent,
                        ),
                    )
                )
        )
        content()
    }
}

/** Translucent "glass" surface used by panels and cards. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = AmplifyCardShape,
    color: Color = AmplifyColors.Panel,
    borderColor: Color = AmplifyColors.Hairline,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(color, color.copy(alpha = color.alpha * 0.55f)),
                )
            )
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun AmplifySectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = AmplifyType.eyebrow, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun AmplifyIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 17.dp,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val background = when {
        !enabled -> Color.White.copy(alpha = 0.03f)
        active -> AmplifyColors.AccentSoft
        else -> Color.White.copy(alpha = 0.06f)
    }
    val tint = when {
        !enabled -> AmplifyColors.TextMuted.copy(alpha = 0.4f)
        active -> AmplifyColors.Accent
        else -> AmplifyColors.TextSecondary
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .amplifyClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Pill button with an optional leading icon; used for Shuffle, Edit, speed, etc. */
@Composable
fun AmplifyPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    compact: Boolean = false,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AmplifyColors.AccentSoft else Color.White.copy(alpha = 0.06f))
            .border(
                1.dp,
                if (selected) AmplifyColors.Accent.copy(alpha = 0.55f) else AmplifyColors.Hairline,
                RoundedCornerShape(50),
            )
            .amplifyClickable(onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 13.dp, vertical = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) AmplifyColors.Accent else AmplifyColors.TextSecondary,
                modifier = Modifier.size(if (compact) 13.dp else 15.dp),
            )
        }
        Text(
            text,
            style = AmplifyType.label,
            color = if (selected) AmplifyColors.Accent else AmplifyColors.TextSecondary,
            maxLines = 1,
        )
    }
}

/** Caret-controlled dropdown that matches the sort selectors in the reference design. */
@Composable
fun <T> AmplifySelect(
    options: List<T>,
    optionLabel: (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leadingLabel: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, AmplifyColors.Hairline, RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingLabel != null) {
                Text(leadingLabel, style = AmplifyType.meta, maxLines = 1)
            }
            Text(
                optionLabel(selected),
                style = AmplifyType.label,
                color = AmplifyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = AmplifyColors.TextMuted,
                modifier = Modifier.size(15.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = AmplifyColors.Menu,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            optionLabel(option),
                            style = AmplifyType.label,
                            color = if (isSelected) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = AmplifyColors.Accent,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = AmplifyColors.TextPrimary,
                        trailingIconColor = AmplifyColors.Accent,
                    ),
                )
            }
        }
    }
}

/** Compact search field used by the sidebar and the playlist toolbar. */
@Composable
fun AmplifySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search tracks",
    requestFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    androidx.compose.runtime.LaunchedEffect(requestFocus) {
        if (requestFocus) runCatching { focusRequester.requestFocus() }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = AmplifyType.label.copy(color = AmplifyColors.TextPrimary),
        cursorBrush = SolidColor(AmplifyColors.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, AmplifyColors.Hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = AmplifyColors.TextMuted,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = AmplifyType.label, color = AmplifyColors.TextMuted, maxLines = 1)
                    }
                    innerTextField()
                }
            }
        },
    )
}

/** Small animated equalizer marking the track that is currently playing. */
@Composable
fun AmplifyEqualizer(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = AmplifyColors.Accent,
    barCount: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "amplify-equalizer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1100, easing = LinearEasing)),
        label = "amplify-equalizer-phase",
    )
    Canvas(modifier.size(width = 14.dp, height = 13.dp)) {
        val gap = size.width / (barCount * 3f - 1f)
        val barWidth = gap * 2f
        repeat(barCount) {
            val index = it
            val wave = kotlin.math.sin((phase * 2f * Math.PI + index * 1.9).toFloat())
            val amplitude = if (playing) 0.32f + 0.68f * kotlin.math.abs(wave) else 0.28f
            val barHeight = size.height * amplitude
            drawRoundRect(
                color = if (playing) color else color.copy(alpha = 0.45f),
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Hairline divider tuned for the dark glass surfaces. */
@Composable
fun AmplifyDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AmplifyColors.Hairline)
    )
}
