package co.electriccoin.zcash.ui.screen.donation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiChipButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTextFieldDefaults
import co.electriccoin.zcash.ui.design.component.ZashiTextField
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.stringRes

@Suppress("LongParameterList", "LongMethod", "MagicNumber")
@Composable
internal fun DonationView(
    state: DonationViewState,
    onToggleAuto: (Boolean) -> Unit,
    onPercentChanged: (String) -> Unit,
    onAmountChanged: (String) -> Unit,
    onReview: () -> Unit,
    onSaveAuto: () -> Unit,
    onExternalAddresses: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = !state.busy, onBack = onBack)
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(
                title = stringResource(R.string.private_support_title),
                navigationAction = {
                    ZashiTopAppBarBackNavigation(onBack = { if (!state.busy) onBack() })
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DonationHeading(stringResource(R.string.private_support_once))
            DonationInput(
                label = stringResource(R.string.private_support_amount),
                value = state.amount,
                suffix = "ZEC",
                enabled = !state.busy,
                onValueChange = onAmountChanged
            )
            ZashiButton(
                text = stringResource(R.string.private_support_review),
                onClick = onReview,
                enabled = state.canReview && !state.busy,
                isLoading = state.busy,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.error) {
                DonationMessage(stringResource(R.string.private_support_failed), isError = true)
            }
            if (state.unsupported) {
                DonationMessage(stringResource(R.string.private_support_software_only))
            }
            DonationHeading(
                text = stringResource(R.string.private_support_auto_title),
                modifier = Modifier.padding(top = 16.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DonationChoice(
                    label = stringResource(R.string.private_support_auto_off),
                    selected = !state.automationEnabled,
                    enabled = !state.busy,
                    onClick = { onToggleAuto(false) }
                )
                DonationChoice(
                    label = stringResource(R.string.private_support_auto_on),
                    selected = state.automationEnabled,
                    enabled = !state.busy,
                    onClick = { onToggleAuto(true) }
                )
            }
            DonationMessage(stringResource(R.string.private_support_auto_description))
            if (state.automationEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("0.25", "0.5", "1").forEach { value ->
                        DonationChoice(
                            label = "$value%",
                            selected = state.percent.toBigDecimalOrNull()?.compareTo(value.toBigDecimal()) == 0,
                            enabled = !state.busy,
                            onClick = { onPercentChanged(value) }
                        )
                    }
                }
                DonationInput(
                    label = stringResource(R.string.private_support_auto_percent),
                    value = state.percent,
                    suffix = "%",
                    enabled = !state.busy,
                    onValueChange = onPercentChanged
                )
            }
            if (state.automationEnabled) {
                ZashiButton(
                    text = stringResource(R.string.private_support_auto_save),
                    onClick = onSaveAuto,
                    enabled = state.canSaveAuto && !state.busy,
                    colors = ZashiButtonDefaults.secondaryColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.automationSaved) {
                    DonationMessage(stringResource(R.string.private_support_auto_saved, state.percent))
                }
            }
            state.autoOutcome?.let { DonationMessage(it) }
            ZashiButton(
                text = stringResource(R.string.private_support_addresses),
                onClick = onExternalAddresses,
                enabled = !state.busy,
                colors = ZashiButtonDefaults.secondaryColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun DonationHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = ZashiTypography.textLg,
        fontWeight = FontWeight.SemiBold,
        color = ZashiColors.Text.textPrimary
    )
}

@Composable
private fun DonationMessage(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = ZashiTypography.textSm,
        color = if (isError) ZashiColors.Text.textError else ZashiColors.Text.textTertiary
    )
}

@Composable
private fun DonationChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ZashiChipButton(
        state = ChipButtonState(
            text = stringRes(label),
            startIcon = if (selected) co.electriccoin.zcash.ui.design.R.drawable.ic_radio_button_checked else null,
            isEnabled = enabled,
            onClick = onClick
        ),
        modifier = Modifier.semantics { this.selected = selected }
    )
}

@Composable
private fun DonationInput(
    label: String,
    value: String,
    suffix: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = ZashiTypography.textSm, color = ZashiColors.Text.textPrimary)
        ZashiTextField(
            value = value,
            onValueChange = onValueChange,
            isEnabled = enabled,
            singleLine = true,
            suffix = { Text(suffix, style = ZashiTypography.textMd, color = ZashiColors.Text.textTertiary) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
            innerModifier = ZashiTextFieldDefaults.innerModifier.semantics { contentDescription = label }
        )
    }
}

@PreviewScreens
@Composable
private fun DonationOffPreview() = ZcashTheme {
    DonationPreview(DonationViewState(amount = "0.01", canReview = true))
}

@PreviewScreens
@Composable
private fun DonationOnPreview() = ZcashTheme {
    DonationPreview(DonationViewState(automationEnabled = true, percent = "0.5", automationSaved = true))
}

@Composable
private fun DonationPreview(state: DonationViewState) {
    DonationView(
        state = state,
        onToggleAuto = {},
        onPercentChanged = {},
        onAmountChanged = {},
        onReview = {},
        onSaveAuto = {},
        onExternalAddresses = {},
        onBack = {}
    )
}