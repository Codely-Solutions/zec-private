package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.MetadataRepository

class CreateOrUpdateTransactionNoteUseCase(
    private val metadataRepository: MetadataRepository,
    private val navigationRouter: NavigationRouter,
    private val showError: ShowErrorUseCase
) {
    /**
     * Saves the note, leaves the note sheet and returns whether the note was saved, showing the
     * generic error when it was not.
     */
    suspend operator fun invoke(txId: String, note: String, accountUuid: AccountUuid, token: Long): Boolean {
        val isSaved = metadataRepository.createOrUpdateTxNote(txId, note.trim(), accountUuid, token)
        navigationRouter.back()
        if (!isSaved) showError()
        return isSaved
    }
}
