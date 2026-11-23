package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DeleteTransactionNoteUseCase(
    private val metadataRepository: MetadataRepository,
    private val navigationRouter: NavigationRouter,
    private val showError: ShowErrorUseCase
) {
    /**
     * Leaves the editor after acknowledged deletion; failure retains its draft and retry actions.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(txId: String, accountUuid: AccountUuid, token: Long): Boolean {
        val isDeleted = try {
            metadataRepository.deleteTxNote(txId, accountUuid, token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        currentCoroutineContext().ensureActive()
        if (isDeleted) navigationRouter.back()
        if (!isDeleted) showError()
        return isDeleted
    }
}
