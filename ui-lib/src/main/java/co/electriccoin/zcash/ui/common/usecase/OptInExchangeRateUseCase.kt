package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider

class OptInExchangeRateUseCase(
    private val navigationRouter: NavigationRouter,
    private val isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
) {
    suspend operator fun invoke(optIn: Boolean, fiatCurrency: FiatCurrency? = null, sourceEntryId: String? = null) {
        isExchangeRateEnabledStorageProvider.store(optIn, fiatCurrency)
        navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.Back }
    }
}
