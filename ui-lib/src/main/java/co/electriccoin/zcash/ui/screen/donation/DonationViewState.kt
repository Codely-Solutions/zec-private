package co.electriccoin.zcash.ui.screen.donation

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState

internal data class DonationViewState(
    val amountState: AmountState = AmountState.newFromZec("", "", false, ExchangeRateState.OptedOut),
    val memoState: MemoState = MemoState.new(""),
    val memoExpanded: Boolean = false,
    val exchangeRateState: ExchangeRateState = ExchangeRateState.OptedOut,
    val spendableShieldedBalance: Zatoshi? = null,
    val busy: Boolean = false,
    val error: Boolean = false,
    val unsupported: Boolean = false,
    val canReview: Boolean = false,
)
