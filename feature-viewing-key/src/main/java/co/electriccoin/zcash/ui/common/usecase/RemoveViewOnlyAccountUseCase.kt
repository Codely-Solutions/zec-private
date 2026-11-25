package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.viewingkey.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes a view-only account from this device, shaped like DisconnectUseCase for a hardware wallet:
 * the user authenticates first, any migration work scheduled for the account is cancelled, the SDK
 * account is deleted, and the account flow supplies the fallback if the selection is gone.
 * The address book and account metadata remain. Reset Zodl owns full metadata removal.
 *
 * Resolve the ID to a view-only account before asking for authentication.
 */
internal class RemoveViewOnlyAccountUseCase(
    private val getViewOnlyAccount: GetViewOnlyAccountUseCase,
    private val accountDataSource: AccountDataSource,
    private val biometricRepository: BiometricRepository,
    private val migrationAppHooks: MigrationAppHooks,
) {
    /**
     * @return false when there was no view-only account to remove.
     * @throws co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException if the user
     *         dismissed the authentication; nothing was removed.
     * @throws co.electriccoin.zcash.ui.common.repository.BiometricsFailureException if the
     *         authentication failed; nothing was removed.
     */
    suspend operator fun invoke(accountKeyId: String): Boolean =
        withContext(Dispatchers.IO) {
            val account = getViewOnlyAccount(accountKeyId) ?: return@withContext false

            biometricRepository.requestBiometrics(
                BiometricRequest(message = stringRes(R.string.view_only_remove_biometric_message))
            )

            migrationAppHooks.cancelMigrationWork(accountKeyId)

            accountDataSource.deleteAccount(account)
            true
        }
}
