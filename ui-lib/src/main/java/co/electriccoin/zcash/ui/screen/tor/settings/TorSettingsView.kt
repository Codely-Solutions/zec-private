package co.electriccoin.zcash.ui.screen.tor.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiBaseSettingsOptIn
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.DialogView
import co.electriccoin.zcash.ui.screen.error.ErrorState
import co.electriccoin.zcash.ui.screen.exchangerate.settings.Option

@Composable
internal fun TorRestartRequiredView(onOpenSettings: () -> Unit) {
    DialogView(
        ErrorState(
            title = stringRes(R.string.tor_protection_restart_title),
            message = stringRes(R.string.tor_protection_restart_message),
            positive = ButtonState(
                text = stringRes(R.string.tor_protection_open_android_settings),
                onClick = onOpenSettings,
            ),
            negative = null,
            onBack = {},
        )
    )
}

@Composable
internal fun TorSettingsLoadView(isLoading: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    ZashiBaseSettingsOptIn(
        header = stringResource(R.string.torSetup_title),
        image = R.drawable.ic_tor_settings,
        onDismiss = onBack,
        info = null,
        content = {
            Spacer(modifier = Modifier.height(16.dp))
            if (isLoading) {
                CircularProgressIndicator(color = ZashiColors.Text.textPrimary)
            } else {
                Text(
                    text = stringResource(R.string.tor_settings_read_failed),
                    style = ZashiTypography.textSm,
                    color = ZashiColors.Text.textTertiary,
                )
            }
        },
        footer = {
            if (!isLoading) {
                ZashiButton(
                    modifier = Modifier.fillMaxWidth(),
                    state = ButtonState(stringRes(R.string.wallet_initialization_retry), onClick = onRetry),
                )
            }
        },
    )
}

@Composable
fun TorSettingsView(state: TorSettingsState) {
    var isOptInSelected by remember(state.isOptedIn) { mutableStateOf(state.isOptedIn) }

    val isButtonDisabled = state.isOptedIn == isOptInSelected

    ZashiBaseSettingsOptIn(
        header = stringResource(R.string.torSetup_title),
        image = R.drawable.ic_tor_settings,
        onDismiss = state.onDismiss,
        info = null,
        content = {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tor_settings_subtitle),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Option(
                modifier = Modifier.fillMaxWidth(),
                isChecked = isOptInSelected,
                title = stringResource(R.string.torSetup_ccSheet_enable),
                subtitle = stringResource(R.string.torSetup_enableDesc),
                onClick = { isOptInSelected = true }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Option(
                modifier = Modifier.fillMaxWidth(),
                isChecked = !isOptInSelected,
                title = stringResource(R.string.currencyConversion_learnMoreOptionDisable),
                subtitle = stringResource(R.string.torSetup_disableDesc),
                onClick = { isOptInSelected = false }
            )
        },
        footer = {
            ZashiButton(
                text = stringResource(R.string.currencyConversion_saveBtn),
                modifier = Modifier.fillMaxWidth(),
                onClick = { state.onSaveClick(isOptInSelected) },
                enabled = !isButtonDisabled,
                colors = ZashiButtonDefaults.primaryColors(),
                hapticFeedbackType = HapticFeedbackType.Confirm
            )
        },
    )
}

@PreviewScreens
@Composable
private fun SettingsExchangeRateOptInPreview() =
    ZcashTheme {
        BlankSurface {
            TorSettingsView(
                state =
                    TorSettingsState(
                        isOptedIn = true,
                        onSaveClick = {},
                        onDismiss = {}
                    )
            )
        }
    }
