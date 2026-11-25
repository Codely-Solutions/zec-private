package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.recoverableReads
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class IsTorEnabledUseCase(
    private val isTorEnabledStorageProvider: IsTorEnabledStorageProvider
) {
    fun observe(): Flow<Boolean?> =
        isTorEnabledStorageProvider
            .observe()
            .recoverableReads()
            .map { result -> result.map { it == true }.getOrNull() }
            .distinctUntilChanged()
}
