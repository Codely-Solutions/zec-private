package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

fun SwapRecipientValidation.errorText(network: String?): StringResource? =
    when (error) {
        SwapRecipientError.ADDRESS -> stringRes(R.string.private_recipient_invalid, network.orEmpty().uppercase())
        SwapRecipientError.NETWORK -> stringRes(R.string.private_recipient_network, network.orEmpty().uppercase())
        SwapRecipientError.ASSET -> stringRes(R.string.private_recipient_asset)
        SwapRecipientError.PAYMENT_AMOUNT -> stringRes(R.string.private_recipient_amount)
        SwapRecipientError.URI -> stringRes(R.string.private_recipient_uri)
        SwapRecipientError.GIFT_LINK -> stringRes(R.string.swap_error_giftCardLink)
        null -> null
    }
