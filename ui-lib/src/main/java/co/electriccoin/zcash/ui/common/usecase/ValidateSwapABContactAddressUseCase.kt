package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.GiftCardSecretDetector
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

class ValidateSwapABContactAddressUseCase(
    private val addressBookRepository: AddressBookRepository,
) {
    suspend operator fun invoke(
        address: String,
        blockchain: SwapBlockchain?,
        exclude: EnhancedABContact? = null
    ): ContactAddressValidationResult =
        when {
            GiftCardSecretDetector.mayContainGiftCardSecret(address) -> ContactAddressValidationResult.GiftCardLink

            blockchain == null || !validateSwapRecipient(address, blockchain.chainTicker).isValid ->
                ContactAddressValidationResult.Invalid

            addressBookRepository.contacts
                .filterNotNull()
                .first()
                .filter { it.blockchain != null }
                .filter {
                    if (exclude == null) true else it != exclude
                }.any {
                    it.address == validateSwapRecipient(address, blockchain.chainTicker).input?.address &&
                        it.blockchain?.chainTicker.equals(blockchain.chainTicker, ignoreCase = true)
                } -> ContactAddressValidationResult.NotUnique

            else -> ContactAddressValidationResult.Valid
        }
}
