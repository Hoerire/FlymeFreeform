package io.github.hoerire.free.bubble

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * Material 3 调色板：优先取系统 Monet 动态色，取不到时回退到青绿色静态色板。
 *
 * 与预览卡一起从原实现转写，色值与判断顺序保持一致。
 */
internal class MaterialPalette private constructor(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val surface: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val dark: Boolean,
) {
    companion object {
        fun resolve(context: Context): MaterialPalette {
            val mode =
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val dark = mode == Configuration.UI_MODE_NIGHT_YES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return fromMonet(context, dark)
            }
            return if (dark) darkPalette() else lightPalette()
        }

        private fun fromMonet(context: Context, dark: Boolean): MaterialPalette {
            val colors: IntArray
            if (dark) {
                colors =
                    intArrayOf(
                        systemColor(context, android.R.color.system_accent1_200),
                        systemColor(context, android.R.color.system_accent1_800),
                        systemColor(context, android.R.color.system_accent1_700),
                        systemColor(context, android.R.color.system_accent1_100),
                        systemColor(context, android.R.color.system_accent2_700),
                        systemColor(context, android.R.color.system_accent2_100),
                        systemColor(context, android.R.color.system_accent3_700),
                        systemColor(context, android.R.color.system_accent3_100),
                        systemColor(context, android.R.color.system_neutral2_900),
                        systemColor(context, android.R.color.system_neutral2_900),
                        systemColor(context, android.R.color.system_neutral2_800),
                        systemColor(context, android.R.color.system_neutral2_700),
                        systemColor(context, android.R.color.system_neutral2_600),
                        systemColor(context, android.R.color.system_neutral1_100),
                        systemColor(context, android.R.color.system_neutral2_200),
                        systemColor(context, android.R.color.system_neutral2_400),
                        systemColor(context, android.R.color.system_neutral2_700),
                    )
            } else {
                colors =
                    intArrayOf(
                        systemColor(context, android.R.color.system_accent1_600),
                        systemColor(context, android.R.color.system_accent1_0),
                        systemColor(context, android.R.color.system_accent1_100),
                        systemColor(context, android.R.color.system_accent1_900),
                        systemColor(context, android.R.color.system_accent2_100),
                        systemColor(context, android.R.color.system_accent2_900),
                        systemColor(context, android.R.color.system_accent3_100),
                        systemColor(context, android.R.color.system_accent3_900),
                        systemColor(context, android.R.color.system_neutral2_0),
                        systemColor(context, android.R.color.system_neutral2_0),
                        systemColor(context, android.R.color.system_neutral2_50),
                        systemColor(context, android.R.color.system_neutral2_100),
                        systemColor(context, android.R.color.system_neutral2_200),
                        systemColor(context, android.R.color.system_neutral1_900),
                        systemColor(context, android.R.color.system_neutral2_700),
                        systemColor(context, android.R.color.system_neutral2_500),
                        systemColor(context, android.R.color.system_neutral2_300),
                    )
            }
            for (color in colors) {
                if (color == 0) return if (dark) darkPalette() else lightPalette()
            }
            return MaterialPalette(
                colors[0], colors[1], colors[2], colors[3], colors[4], colors[5],
                colors[6], colors[7], colors[8], colors[9], colors[10], colors[11],
                colors[12], colors[13], colors[14], colors[15], colors[16], dark,
            )
        }

        private fun systemColor(context: Context, id: Int): Int =
            runCatching { context.getColor(id) }.getOrDefault(0)

        private fun lightPalette(): MaterialPalette =
            MaterialPalette(
                0xFF00696B.toInt(), 0xFFFFFFFF.toInt(), 0xFF9CF1F0.toInt(), 0xFF002021.toInt(),
                0xFFCCE8E7.toInt(), 0xFF051F20.toInt(), 0xFFFFD8F0.toInt(), 0xFF31162B.toInt(),
                0xFFF5FAF9.toInt(), 0xFFF5FAF9.toInt(), 0xFFECF2F1.toInt(), 0xFFE1E7E6.toInt(),
                0xFFD6DCDC.toInt(), 0xFF171D1D.toInt(), 0xFF3F4948.toInt(), 0xFF6F7979.toInt(),
                0xFFBEC9C8.toInt(), false,
            )

        private fun darkPalette(): MaterialPalette =
            MaterialPalette(
                0xFF80D4D3.toInt(), 0xFF003737.toInt(), 0xFF004F4F.toInt(), 0xFF9CF1F0.toInt(),
                0xFF334B4A.toInt(), 0xFFCCE8E7.toInt(), 0xFF5E3D53.toInt(), 0xFFFAD7EC.toInt(),
                0xFF0E1414.toInt(), 0xFF0E1414.toInt(), 0xFF161C1C.toInt(), 0xFF202625.toInt(),
                0xFF2A2F2E.toInt(), 0xFFDDE4E3.toInt(), 0xFFBEC9C8.toInt(), 0xFF889392.toInt(),
                0xFF3F4948.toInt(), true,
            )
    }
}
