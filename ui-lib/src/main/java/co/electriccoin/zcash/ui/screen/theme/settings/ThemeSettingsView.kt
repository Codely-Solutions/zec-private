package co.electriccoin.zcash.ui.screen.theme.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiBaseSettingsOptIn
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.component.ZodlRadioOptionCard
import co.electriccoin.zcash.ui.design.component.rememberZashiFrostState
import co.electriccoin.zcash.ui.design.component.zashiFrostSource
import co.electriccoin.zcash.ui.design.component.zashiFrostedFooter
import co.electriccoin.zcash.ui.design.component.zashiFrostedHeader
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.dimensions.ZashiDimensions
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.ScreenLogoHeader

private val CONTENT_TOP_SPACING = 28.dp

@Composable
internal fun ThemeSettingsLoadView(isLoading: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    ZashiBaseSettingsOptIn(
        header = stringResource(R.string.theme_settings_title),
        image = R.drawable.ic_settings_theme,
        info = null,
        onDismiss = onBack,
        footer = {},
        content = {
            Spacer(modifier = Modifier.height(16.dp))
            AppearanceReadContent(isLoading, onRetry)
        },
    )
}

@Composable
internal fun AppearanceReadContent(isLoading: Boolean, onRetry: () -> Unit) {
    if (isLoading) {
        CircularProgressIndicator(color = ZashiColors.Text.textPrimary)
    } else {
        Text(
            text = stringResource(R.string.theme_settings_read_failed),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = ZashiTypography.textSm,
            color = ZashiColors.Text.textTertiary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        ZashiButton(
            modifier = Modifier.fillMaxWidth(),
            state = ButtonState(stringRes(R.string.wallet_initialization_retry), onClick = onRetry),
        )
    }
}

@Composable
internal fun ThemeSettingsView(state: ThemeSettingsState) {
    val hazeState = rememberZashiFrostState()
    BlankBgScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            ZashiSmallTopAppBar(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .zashiFrostedHeader(hazeState),
                navigationAction = {
                    ZashiTopAppBarBackNavigation(onBack = state.onBack)
                },
                colors =
                    ZcashTheme.colors.topAppBarColors.copyColors(
                        containerColor = Color.Transparent
                    ),
            )
        },
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .zashiFrostedFooter(hazeState)
                        .padding(horizontal = ZashiDimensions.Spacing.spacing3xl)
            ) {
                if (state.hasSaveError) AppearanceSaveFailure()
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    state = state.saveButton,
                    defaultPrimaryColors = ZashiButtonDefaults.primaryColors(),
                )
                Spacer(modifier = Modifier.height(28.dp))
                Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
            }
        }
    ) { paddingValues ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .zashiFrostSource(hazeState)
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .scaffoldPadding(
                            paddingValues = paddingValues,
                            top = paddingValues.calculateTopPadding() + CONTENT_TOP_SPACING
                        )
            ) {
                ScreenLogoHeader(icon = R.drawable.ic_settings_theme)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.theme_settings_title),
                    style = ZashiTypography.header6,
                    fontWeight = FontWeight.SemiBold,
                    color = ZashiColors.Text.textPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.theme_settings_subtitle),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textTertiary,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Column(modifier = Modifier.selectableGroup()) {
                    state.options.forEachIndexed { index, option ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        ZodlRadioOptionCard(
                            modifier = Modifier.fillMaxWidth(),
                            isChecked = option.isChecked,
                            title = stringResource(option.mode.titleRes),
                            subtitle = stringResource(option.mode.subtitleRes),
                            onClick = option.onClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppearanceSaveFailure() {
    Text(
        text = stringResource(R.string.appearance_save_failed),
        modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textError,
    )
}

private val AppearanceMode.labels: Pair<Int, Int>
    get() =
        when (this) {
            AppearanceMode.SYSTEM -> R.string.theme_settings_option_system to R.string.theme_settings_option_system_desc
            AppearanceMode.LIGHT -> R.string.theme_settings_option_light to R.string.theme_settings_option_light_desc
            AppearanceMode.DARK -> R.string.theme_settings_option_dark to R.string.theme_settings_option_dark_desc
        }

private val AppearanceMode.titleRes: Int
    get() = labels.first

private val AppearanceMode.subtitleRes: Int
    get() = labels.second

@Suppress("UnusedPrivateMember")
@PreviewScreens
@Composable
private fun ThemeSettingsPreview() =
    ZcashTheme {
        ThemeSettingsView(state = previewState(selected = AppearanceMode.SYSTEM))
    }

@Suppress("UnusedPrivateMember")
@PreviewScreens
@Composable
private fun ThemeSettingsDarkPreview() =
    ZcashTheme(appearanceMode = AppearanceMode.DARK) {
        ThemeSettingsView(state = previewState(selected = AppearanceMode.DARK))
    }

@Suppress("UnusedPrivateMember")
@PreviewScreens
@Composable
private fun ThemeSettingsLightPreview() =
    ZcashTheme {
        ThemeSettingsView(state = previewState(selected = AppearanceMode.LIGHT))
    }

private fun previewState(selected: AppearanceMode) =
    ThemeSettingsState(
        options =
            listOf(AppearanceMode.SYSTEM, AppearanceMode.LIGHT, AppearanceMode.DARK).map { mode ->
                AppearanceModeOptionState(
                    mode = mode,
                    isChecked = mode == selected,
                    onClick = {}
                )
            },
        saveButton =
            ButtonState(
                text = stringRes(R.string.currencyConversion_saveBtn),
                onClick = {}
            ),
        onBack = {}
    )
