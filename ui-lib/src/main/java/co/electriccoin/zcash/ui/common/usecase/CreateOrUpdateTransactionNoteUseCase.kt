package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class CreateOrUpdateTransactionNoteUseCase(
    private val metadataRepository: MetadataRepository,
    private val navigationRouter: NavigationRouter,
    private val showError: ShowErrorUseCase
) {
    /**
     * Leaves the editor only after acknowledged persistence. Failed or uncertain writes retain
     * the draft so the same account-bound edit can be retried.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(txId: String, note: String, accountUuid: AccountUuid, token: Long): Boolean {
        val isSaved = try {
            metadataRepository.createOrUpdateTxNote(txId, note.trim(), accountUuid, token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        currentCoroutineContext().ensureActive()
        if (isSaved) navigationRouter.back()
        if (!isSaved) showError()
        return isSaved
    }
}
