package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.validateSwapRecipient
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.GiftCardSecretDetector

/**
 * Updates a contact and goes back.
 *
 * Refuses, independently of the screen's validation, an address that is or may contain a gift card link or key: it
 * then returns [ABContactSaveResult.GiftCardLinkRefused] without saving anything or navigating, so that a screen whose
 * validation has not caught up with a pasted link yet can show why.
 */
class UpdateABContactUseCase(
    private val addressBookRepository: AddressBookRepository,
    private val navigationRouter: NavigationRouter
) {
    operator fun invoke(
        contact: EnhancedABContact,
        name: String,
        address: String,
        chain: String?,
    ): ABContactSaveResult {
        if (GiftCardSecretDetector.mayContainGiftCardSecret(address)) return ABContactSaveResult.GiftCardLinkRefused
        val recipient = chain?.let { validateSwapRecipient(address, it, allowPaymentAmount = false) }
        if (recipient != null && !recipient.isValid) return ABContactSaveResult.InvalidAddress
        val receivingAddress = recipient?.input?.address ?: address
        addressBookRepository.updateContact(contact = contact, name = name, address = receivingAddress, chain = chain)
        navigationRouter.back()
        return ABContactSaveResult.Saved
    }
}
