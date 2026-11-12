package co.electriccoin.zcash.ui.screen.swap.quote

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.isZCashAsset
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.withStyle

internal fun SwapQuote.minimumDepositInfo(): SwapQuoteInfoItem? {
    if (mode != SwapMode.FLEX_INPUT || !destinationAsset.isZCashAsset) return null
    return minimumAmountIn?.let { minimum ->
        SwapQuoteInfoItem(
            description = stringRes(R.string.private_minimum_deposit),
            title = stringResByCurrencyNumber(
                minimum, originAsset.tokenTicker, minDecimals = 0, maxDecimals = originAsset.decimals,
            ).withStyle(),
            help = stringRes(R.string.private_minimum_deposit_help),
        )
    }
}
