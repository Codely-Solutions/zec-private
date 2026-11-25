package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.widget.Toast
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val isLoading = MutableStateFlow(false)

    private var job: Job? = null

    @Suppress("TooGenericExceptionCaught")
    operator fun invoke(address: String, sourceEntryId: String?, onFailure: () -> Unit = {}) {
        if (job?.isActive == true || !navigationRouter.isAtEntry(sourceEntryId)) return
        val account = selectedAccount() ?: return
        val grant = appAccessSession.grant.value
        val token = walletWriteAccess.token()
        val canRecover = {
            appAccessSession.permits(grant) && walletWriteAccess.permits(token) && selectedAccount() == account
        }
        job = scope.launch {
            try {
                isLoading.update { true }
                recover(address, sourceEntryId, account, canRecover)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (canRecover() && navigationRouter.isAtEntry(sourceEntryId)) onFailure()
            } finally {
                isLoading.update { false }
            }
        }
    }

    private suspend fun recover(
        address: String,
        sourceEntryId: String?,
        account: AccountUuid,
        canRecover: () -> Boolean,
    ) {
        if (isTorEnabled.get() != true || !canRecover() || !navigationRouter.isAtEntry(sourceEntryId)) return

        val synchronizer = synchronizerProvider.getSynchronizer()
        val ephemeral = resolveEphemeralAddress(synchronizer, address)
        if (ephemeral != null && isTorEnabled.get() == true) {
            val committed = navigationRouter.commitFrom(sourceEntryId, canRecover) { NavigationCommand.Back }
            if (committed && canRecover()) fetchUtxosByAddress(synchronizer, account, ephemeral)
        }
    }

    fun observeIsLoading() = isLoading.asStateFlow()

    private fun selectedAccount() =
        accountDataSource.allAccounts.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid

    private suspend fun resolveEphemeralAddress(synchronizer: Synchronizer, address: String): String? =
        when (synchronizer.validateAddress(address)) {
            AddressType.Shielded, AddressType.Tex, AddressType.Unified -> null
            AddressType.Transparent -> address
            is AddressType.Invalid -> getSwapStatus(depositAddress = address).status?.destinationAddress?.address
        }

    private suspend fun fetchUtxosByAddress(synchronizer: Synchronizer, account: AccountUuid, address: String) {
        val fundsDiscovered = synchronizer.fetchUtxosByAddress(accountUuid = account, address = address)
        withContext(Dispatchers.Main.immediate) {
            if (fundsDiscovered) {
                Toast.makeText(context, "Funds were successfully discovered", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "No funds were discovered", Toast.LENGTH_LONG).show()
            }
        }
    }
}
