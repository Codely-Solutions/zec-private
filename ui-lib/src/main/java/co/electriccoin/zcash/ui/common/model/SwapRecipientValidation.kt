package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.common.repository.GiftCardSecretDetector

enum class SwapRecipientError { ADDRESS, NETWORK, ASSET, URI, PAYMENT_AMOUNT, GIFT_LINK }

data class SwapRecipientValidation(
    val input: RecipientAddressPolicy.RecipientInput? = null,
    val error: SwapRecipientError? = null,
) {
    val isValid: Boolean get() = input != null && error == null
}

/** One local policy for entry, saved contacts and the final quote boundary. */
@Suppress("ReturnCount") // Fail-closed guards preserve the specific input error.
fun validateSwapRecipient(
    value: String,
    network: String?,
    token: String? = null,
    contactNetwork: String? = null,
    allowPaymentAmount: Boolean = true,
): SwapRecipientValidation {
    if (GiftCardSecretDetector.mayContainGiftCardSecret(value)) {
        return SwapRecipientValidation(error = SwapRecipientError.GIFT_LINK)
    }
    if (value.isEmpty() || network == null) return SwapRecipientValidation()
    val parsed = RecipientAddressPolicy.parse(value.trim())
    if (parsed !is RecipientAddressPolicy.ParseResult.Parsed) {
        return SwapRecipientValidation(error = SwapRecipientError.URI)
    }
    val input = parsed.input
    return validateParsedRecipient(input, network, token, contactNetwork, allowPaymentAmount)
}

@Suppress("ReturnCount") // Independent validation failures retain their own error classification.
private fun validateParsedRecipient(
    input: RecipientAddressPolicy.RecipientInput,
    network: String,
    token: String?,
    contactNetwork: String?,
    allowPaymentAmount: Boolean,
): SwapRecipientValidation {
    if (!allowPaymentAmount && input.amount != null) {
        return SwapRecipientValidation(error = SwapRecipientError.PAYMENT_AMOUNT)
    }
    if (!contactNetwork.matchesNetwork(network) || !input.network.matchesNetwork(network)) {
        return SwapRecipientValidation(error = SwapRecipientError.NETWORK)
    }
    if (token != null && input.asset != null && !input.asset.equals(token, ignoreCase = true)) {
        return SwapRecipientValidation(error = SwapRecipientError.ASSET)
    }
    val syntax = RecipientAddressPolicy.validate(input.address, network.lowercase())
    if (syntax !is RecipientAddressPolicy.Validation.Valid) {
        return SwapRecipientValidation(error = SwapRecipientError.ADDRESS)
    }
    return SwapRecipientValidation(
        input = input.copy(address = RecipientAddressPolicy.normalize(input.address, network.lowercase()))
    )
}

private fun String?.matchesNetwork(network: String): Boolean = this == null || equals(network, ignoreCase = true)
