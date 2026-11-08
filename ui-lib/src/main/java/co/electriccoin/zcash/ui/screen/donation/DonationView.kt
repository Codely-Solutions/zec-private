package co.electriccoin.zcash.ui.screen.donation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiButtonDefaults
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarBackNavigation
import co.electriccoin.zcash.ui.design.newcomponent.PreviewScreens
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.screen.send.view.SendFormAmountTextField
import co.electriccoin.zcash.ui.screen.send.view.SendFormMemoTextField

@Suppress("LongParameterList", "LongMethod", "MagicNumber")
@Composable
internal fun DonationView(
    state: DonationViewState,
    onAmountChanged: (AmountState) -> Unit,
    onMemoChanged: (MemoState) -> Unit,
    onToggleMemo: () -> Unit,
    onReview: () -> Unit,
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
            DonationMessage(stringResource(R.string.private_support_description))
            SendFormAmountTextField(
                amountState = state.amountState,
                imeAction = if (state.memoExpanded) ImeAction.Next else ImeAction.Done,
                isTransparentOrTextRecipient = false,
                exchangeRateState = state.exchangeRateState,
                setAmountState = onAmountChanged,
                spendableShieldedBalance = state.spendableShieldedBalance,
                enabled = !state.busy,
            )
            ZashiButton(
                text = stringResource(
                    if (state.memoExpanded) {
                        R.string.private_support_hide_message
                    } else {
                        R.string.private_support_add_message
                    }
                ),
                onClick = onToggleMemo,
                enabled = !state.busy,
                colors = ZashiButtonDefaults.tertiaryColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.memoExpanded) {
                SendFormMemoTextField(
                    isMemoFieldAvailable = true,
                    memoState = state.memoState,
                    setMemoState = onMemoChanged,
                    enabled = !state.busy,
                )
            }
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

@PreviewScreens
@Composable
private fun DonationPreviewScreen() = ZcashTheme {
    DonationPreview(
        DonationViewState(
            amountState = AmountState.newFromZec("0.01", "", false, ExchangeRateState.OptedOut),
            canReview = true
        )
    )
}

@Composable
private fun DonationPreview(state: DonationViewState) {
    DonationView(
        state = state,
        onAmountChanged = {},
        onMemoChanged = {},
        onToggleMemo = {},
        onReview = {},
        onExternalAddresses = {},
        onBack = {}
    )
}