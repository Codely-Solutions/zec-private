package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact

class DeleteABContactUseCase(
    private val addressBookRepository: AddressBookRepository,
    private val navigationRouter: NavigationRouter
) {
    suspend operator fun invoke(contact: EnhancedABContact): ABContactSaveResult =
        persistABContact(navigationRouter) { addressBookRepository.deleteContact(contact) }
}
