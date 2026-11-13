// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import io.relais.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// The theme, version 2 (a2): the grammar of a news app, ink on paper, one red accent.
// Every role of the scheme is set (section 1): a role left out takes Material 3's purple, as the
// bottom bar indicator and the snackbar action did in the first v0.2 builds.

val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EEFC), onPrimaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFF2F3F5), onSurfaceVariant = Color(0xFF5B6170),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF2F3F5),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainerHighest = Color(0xFFE9EBEE),
    surfaceTint = Color.Transparent,
    secondary = Color(0xFF5B6170), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8EEFC), onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFF1D4ED8), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE8EEFC), onTertiaryContainer = Color(0xFF1E3A8A),
    inverseSurface = Color(0xFF16181D), inverseOnSurface = Color(0xFFECEDEF), inversePrimary = Color(0xFF8AB4F8),
    outline = Color(0xFFC5C9D0), outlineVariant = Color(0xFFE3E5E8),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF0B1A3A),
    primaryContainer = Color(0xFF1E2A44), onPrimaryContainer = Color(0xFFBFD3FF),
    background = Color(0xFF121316), onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF121316), onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF1D1F24), onSurfaceVariant = Color(0xFFA5ABB6),
    surfaceContainer = Color(0xFF121316), surfaceContainerHigh = Color(0xFF1D1F24),
    surfaceContainerLowest = Color(0xFF0D0E10), surfaceContainerLow = Color(0xFF121316), surfaceContainerHighest = Color(0xFF26292F),
    surfaceTint = Color.Transparent,
    secondary = Color(0xFFA5ABB6), onSecondary = Color(0xFF16181D),
    secondaryContainer = Color(0xFF1E2A44), onSecondaryContainer = Color(0xFFBFD3FF),
    tertiary = Color(0xFF8AB4F8), onTertiary = Color(0xFF0B1A3A),
    tertiaryContainer = Color(0xFF1E2A44), onTertiaryContainer = Color(0xFFBFD3FF),
    inverseSurface = Color(0xFFECEDEF), inverseOnSurface = Color(0xFF16181D), inversePrimary = Color(0xFF1D4ED8),
    outline = Color(0xFF3C4049), outlineVariant = Color(0xFF2A2D33),
)

@Immutable
/**
 * Colors outside Material's scheme. [brand] is the red of the icon's dot (theme-compose.md 11.2,
 * v0.3): it colors the dot of the logotype and nothing else; unread marks stay primary.
 */
data class RelaisExtraColors(val image: Color, val highlight: Color, val pdfBackground: Color, val pdfBar: Color, val brand: Color)

val LightExtra = RelaisExtraColors(Color(0xFFE6E8EB), Color(0xFFFDE68A), Color(0xFF3A3A3A), Color(0xFF232323), Color(0xFFD9442B))
val DarkExtra = RelaisExtraColors(Color(0xFF23262C), Color(0xFF4A3D07), Color(0xFF3A3A3A), Color(0xFF232323), Color(0xFFF06A50))
val LocalRelaisColors = staticCompositionLocalOf { LightExtra }

// Literata, Inter and IBM Plex Sans Condensed (SIL Open Font License 1.1), embedded in res/font;
// licences in licenses/fonts/. Literata and Inter are variable fonts: one file, several weights.
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) =
    Font(resId, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Literata: FontFamily = FontFamily(
    variable(R.font.literata, 400),
    variable(R.font.literata, 600),
    variable(R.font.literata, 700),
)

val Inter: FontFamily = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

val PlexCondensed: FontFamily = FontFamily(
    Font(R.font.plex_condensed_medium, FontWeight.Medium),
    Font(R.font.plex_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.plex_condensed_bold, FontWeight.Bold),
)

val RelaisTypography = Typography(
    displaySmall = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.6.sp),
    titleSmall = TextStyle(fontFamily = Literata, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = Literata, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 31.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = PlexCondensed, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.65.sp),
)

/** Section titles and report sub-headings: condensed, bold, capitals. */
/**
 * Article text sizes, the "Aa" setting (theme-compose.md 9.1): body and report title. Default
 * (17/28 sp) is the third step; 19/31, the size of v0.1.1, is Large. Body subheads derive
 * from the body (ReportScreen, subheadStyle). [button] is the size of its "A" in the control.
 */
enum class TextStep(
    val label: Int,
    val body: TextUnit,
    val line: TextUnit,
    val title: TextUnit,
    val titleLine: TextUnit,
    val button: TextUnit,
) {
    Smaller(R.string.text_step_smaller, 15.sp, 24.sp, 23.sp, 28.sp, 14.sp),
    Small(R.string.text_step_small, 16.sp, 26.sp, 24.sp, 30.sp, 16.sp),
    Default(R.string.text_step_default, 17.sp, 28.sp, 25.sp, 31.sp, 18.sp),
    Large(R.string.text_step_large, 19.sp, 31.sp, 27.sp, 33.sp, 21.sp),
    Larger(R.string.text_step_larger, 21.sp, 34.sp, 29.sp, 35.sp, 24.sp),
    ;

    companion object {
        fun of(index: Int): TextStep = entries[index.coerceIn(entries.indices)]
    }
}

/** The step of the report being read, for its subheads. */
val LocalTextStep = staticCompositionLocalOf { TextStep.Default }

val SectionTitleStyle = TextStyle(fontFamily = PlexCondensed, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.6.sp)

val RelaisShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(24.dp),
)

/**
 * How dense the whole interface is (theme-compose.md 12.6): list titles, kickers, info lines,
 * row padding, margins, filter pills, section titles and Today's large headline. Compact is the
 * default. [reportStep] is where the report text starts ("Aa" can move it on its own).
 */
enum class DisplaySize(
    @StringRes val label: Int,
    val rowTitle: TextUnit,
    val rowTitleLine: TextUnit,
    val kicker: TextUnit,
    val kickerLine: TextUnit,
    val info: TextUnit,
    val infoLine: TextUnit,
    val rowVertical: Dp,
    val kickerGap: Dp,
    val screen: Dp,
    val chip: Dp,
    val chipText: TextUnit,
    val section: TextUnit,
    val sectionLine: TextUnit,
    val heroTitle: TextUnit,
    val heroTitleLine: TextUnit,
    val heroRatio: Float,
    val reportStep: TextStep,
) {
    Compact(
        R.string.display_compact, 15.sp, 20.sp, 11.sp, 14.sp, 12.sp, 16.sp, 9.dp, 3.dp, 14.dp, 30.dp, 13.sp,
        13.sp, 18.sp, 22.sp, 27.sp, 2f, TextStep.Small,
    ),
    Default(
        R.string.display_default, 17.sp, 22.sp, 13.sp, 16.sp, 13.sp, 18.sp, 14.dp, 6.dp, 16.dp, 32.dp, 14.sp,
        15.sp, 20.sp, 25.sp, 31.sp, 16f / 9f, TextStep.Default,
    ),
    Large(
        R.string.display_large, 19.sp, 25.sp, 14.sp, 18.sp, 14.sp, 20.sp, 16.dp, 7.dp, 16.dp, 36.dp, 15.sp,
        16.sp, 21.sp, 28.sp, 34.sp, 16f / 9f, TextStep.Large,
    ),
    ;

    companion object {
        fun of(name: String?): DisplaySize = entries.firstOrNull { it.name == name } ?: Compact
    }
}

val LocalDisplaySize = staticCompositionLocalOf { DisplaySize.Compact }

/** The typography of a display level: list titles, kickers, info lines and the large headline. */
fun typographyFor(d: DisplaySize): Typography = RelaisTypography.copy(
    titleMedium = RelaisTypography.titleMedium.copy(fontSize = d.rowTitle, lineHeight = d.rowTitleLine),
    labelSmall = RelaisTypography.labelSmall.copy(fontSize = d.kicker, lineHeight = d.kickerLine),
    bodySmall = RelaisTypography.bodySmall.copy(fontSize = d.info, lineHeight = d.infoLine),
    displaySmall = RelaisTypography.displaySmall.copy(fontSize = d.heroTitle, lineHeight = d.heroTitleLine),
)

/** Spacing; the margins, row padding and pills follow the display level (12.6). */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val section = 20.dp
    val screen: Dp @Composable @ReadOnlyComposable get() = LocalDisplaySize.current.screen
    val rowVertical: Dp @Composable @ReadOnlyComposable get() = LocalDisplaySize.current.rowVertical
    val kickerGap: Dp @Composable @ReadOnlyComposable get() = LocalDisplaySize.current.kickerGap
    val chip: Dp @Composable @ReadOnlyComposable get() = LocalDisplaySize.current.chip
    val chipText: TextUnit @Composable @ReadOnlyComposable get() = LocalDisplaySize.current.chipText
}

val Hairline = 1.dp
val SectionRule = 2.dp

@Composable
fun RelaisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    display: DisplaySize = DisplaySize.Compact,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalRelaisColors provides if (darkTheme) DarkExtra else LightExtra,
        LocalDisplaySize provides display,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = typographyFor(display),
            shapes = RelaisShapes,
            content = content,
        )
    }
}
