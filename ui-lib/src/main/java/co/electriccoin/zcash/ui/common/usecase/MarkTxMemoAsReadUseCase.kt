package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.repository.MetadataRepository

class MarkTxMemoAsReadUseCase(
    private val metadataRepository: MetadataRepository
) {
    operator fun invoke(txId: String, accountUuid: AccountUuid, token: Long) {
        metadataRepository.markTxMemoAsRead(txId, accountUuid, token)
    }
}
