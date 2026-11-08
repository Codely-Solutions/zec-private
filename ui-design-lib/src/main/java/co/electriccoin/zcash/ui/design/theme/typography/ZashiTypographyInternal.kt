package co.electriccoin.zcash.ui.design.theme.typography

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object ZashiTypographyInternal {
    val header1: TextStyle =
        TextStyle(
            fontSize = 56.sp,
            lineHeight = 68.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header2: TextStyle =
        TextStyle(
            fontSize = 48.sp,
            lineHeight = 60.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header3: TextStyle =
        TextStyle(
            fontSize = 40.sp,
            lineHeight = 52.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header4: TextStyle =
        TextStyle(
            fontSize = 32.sp,
            lineHeight = 40.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header5: TextStyle =
        TextStyle(
            fontSize = 28.sp,
            lineHeight = 40.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val header6: TextStyle =
        TextStyle(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXl: TextStyle =
        TextStyle(
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textLg: TextStyle =
        TextStyle(
            fontSize = 18.sp,
            lineHeight = 28.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textMd: TextStyle =
        TextStyle(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textSm: TextStyle =
        TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXs: TextStyle =
        TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
    val textXxs: TextStyle =
        TextStyle(
            fontSize = 10.sp,
            lineHeight = 18.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Normal,
        )
}

// Keep the existing typography API while using platform fonts without a provider dependency.
val InterFontFamily = FontFamily.Default
val RobotoMonoFontFamily = FontFamily.Monospace
