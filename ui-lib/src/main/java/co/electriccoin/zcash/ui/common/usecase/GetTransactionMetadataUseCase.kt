package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.repository.MetadataRepository

class GetTransactionMetadataUseCase(
    private val metadataRepository: MetadataRepository,
) {
    suspend operator fun invoke(txId: String, accountUuid: AccountUuid, token: Long): String? =
        metadataRepository.readTxNote(txId, accountUuid, token)
}
