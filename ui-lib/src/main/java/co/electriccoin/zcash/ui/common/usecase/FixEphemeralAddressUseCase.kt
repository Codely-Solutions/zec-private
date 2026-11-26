package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.widget.Toast
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataReadUnavailableException
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.TorOnlyRequest
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class FixEphemeralAddressUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val getSwapStatus: GetSwapStatusUseCase,
    private val context: Context,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
    private val isTorEnabled: IsTorEnabledStorageProvider,
) {
    suspend operator fun invoke(address: String, sourceEntryId: String?): DiscoveryOutcome {
        val account = selectedAccount() ?: return DiscoveryOutcome.Unconfirmed
        val grant = appAccessSession.grant.value
        val token = walletWriteAccess.token()
        val canRecover = {
            appAccessSession.permits(grant) && walletWriteAccess.permits(token) &&
                selectedAccount()?.sdkAccount?.accountUuid == account.sdkAccount.accountUuid
        }
        val confirmed = runCatchingRecoverable {
            recover(address, sourceEntryId, account, canRecover)
        }.getOrDefault(false)
        return when {
            !canRecover() -> DiscoveryOutcome.Superseded
            !confirmed -> DiscoveryOutcome.Unconfirmed
            else -> DiscoveryOutcome.Confirmed(
                isCurrent = canRecover,
                ownership = combine(
                    appAccessSession.grant, walletWriteAccess.version, accountDataSource.allAccounts,
                ) { _, _, _ -> canRecover() },
            )
        }
    }

    private suspend fun recover(
        address: String,
        sourceEntryId: String?,
        account: WalletAccount,
        canRecover: () -> Boolean,
    ): Boolean {
        val atRecoveryEntry = { canRecover() && navigationRouter.isForegroundAtEntry(sourceEntryId) }
        val scan = navigationRouter.whileAtEntry(sourceEntryId, requireForeground = true) {
            withTimeoutOrNull(PRE_SCAN_TIMEOUT) {
                if (!atRecoveryEntry() || isTorEnabled.get() != true) {
                    return@withTimeoutOrNull null
                }
                val synchronizer = synchronizerProvider.getSynchronizer()
                val ephemeral = resolveEphemeralAddress(synchronizer, address, account, atRecoveryEntry)
                ephemeral?.takeIf { isTorEnabled.get() == true && atRecoveryEntry() }?.let { synchronizer to it }
            }
        }
        return scan != null && atRecoveryEntry() && runScan(scan, account, sourceEntryId, canRecover)
    }

    private suspend fun runScan(
        scan: Pair<Synchronizer, String>,
        account: WalletAccount,
        sourceEntryId: String?,
        canRecover: () -> Boolean,
    ): Boolean = withContext(Dispatchers.Default) {
        if (!canRecover() || !navigationRouter.isForegroundAtEntry(sourceEntryId)) return@withContext false
        val (synchronizer, address) = scan
        val confirmed = synchronizer.fetchUtxosByAddress(
            accountUuid = account.sdkAccount.accountUuid,
            address = address,
        )
        if (confirmed) {
            withContext(Dispatchers.Main.immediate) {
                navigationRouter.stageResultFrom(sourceEntryId, canRecover) {
                    Toast.makeText(context, R.string.ephemeral_recovery_success, Toast.LENGTH_LONG).show()
                    NavigationCommand.Back
                }
            }
        }
        confirmed
    }

    private fun selectedAccount() =
        accountDataSource.allAccounts.value?.firstOrNull { it.isSelected }

    private suspend fun resolveEphemeralAddress(
        synchronizer: Synchronizer,
        address: String,
        account: WalletAccount,
        canRecover: () -> Boolean,
    ): String? =
        when (synchronizer.validateAddress(address)) {
            AddressType.Shielded, AddressType.Tex, AddressType.Unified -> null
            AddressType.Transparent -> address
            is AddressType.Invalid -> {
                if (!canRecover()) null else withContext(TorOnlyRequest(canRecover)) {
                    getSwapStatus.observeForAccount(address, account, canRecover)
                        .first { !it.isLoading && it.error !is MetadataReadUnavailableException }
                        .status?.destinationAddress?.address
                }
            }
        }

    private companion object {
        val PRE_SCAN_TIMEOUT = 30.seconds
    }
}

sealed interface DiscoveryOutcome {
    data object Unconfirmed : DiscoveryOutcome
    data object Superseded : DiscoveryOutcome
    class Confirmed internal constructor(
        internal val isCurrent: () -> Boolean,
        internal val ownership: Flow<Boolean>,
    ) : DiscoveryOutcome
}
