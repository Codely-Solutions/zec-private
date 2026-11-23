package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.MetadataRepository

class DeleteTransactionNoteUseCase(
    private val metadataRepository: MetadataRepository,
    private val navigationRouter: NavigationRouter,
    private val showError: ShowErrorUseCase
) {
    /**
     * Deletes the note, leaves the note sheet and returns whether the note was deleted, showing the
     * generic error when it was not.
     */
    suspend operator fun invoke(txId: String, accountUuid: AccountUuid, token: Long): Boolean {
        val isDeleted = metadataRepository.deleteTxNote(txId, accountUuid, token)
        navigationRouter.back()
        if (!isDeleted) showError()
        return isDeleted
    }
}
