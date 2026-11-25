package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.model.HWWalletAccount
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.loggableNot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DisconnectUseCase(
    private val accountDataSource: AccountDataSource,
    private val biometricRepository: BiometricRepository,
    private val migrationAppHooks: MigrationAppHooks,
) {
    private val logger = loggableNot("DisconnectUseCase")

    /**
     * A disconnected hardware account must take its scheduled migration work with it — otherwise
     * the lanes zombie-retry for an account that no longer exists. Deleting the account also drops
     * a Ledger account's stored binding. Account deletion clears the stored selection only if it
     * still names the removed account. The account flow supplies its ordinary fallback.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(hwAccount: HWWalletAccount) =
        withContext(Dispatchers.IO) {
            biometricRepository.requestBiometrics(
                BiometricRequest(message = stringRes(R.string.disconnect_hardware_wallet_biometric_message))
            )

            val uuid = hwAccount.sdkAccount.accountUuid
            migrationAppHooks.retireHardwareAccount(uuid.toStorageKeyId()) {
                accountDataSource.deleteAccount(hwAccount)
            }
            logger("Hardware account removed")
        }
}
