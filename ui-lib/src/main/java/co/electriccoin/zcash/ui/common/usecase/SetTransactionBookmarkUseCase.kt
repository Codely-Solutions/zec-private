package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.repository.MetadataRepository

class SetTransactionBookmarkUseCase(
    private val metadataRepository: MetadataRepository,
    private val showError: ShowErrorUseCase
) {
    suspend operator fun invoke(txId: String, isBookmarked: Boolean, accountUuid: AccountUuid, token: Long): Boolean =
        metadataRepository.setTxBookmark(txId, isBookmarked, accountUuid, token).also { isSaved ->
            if (!isSaved) showError()
        }
}
