package co.electriccoin.zcash.global

import android.content.Context
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

internal operator fun WalletCoordinator.Companion.invoke(
    context: Context,
    persistableWalletProvider: PersistableWalletProvider,
    isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    resetProgressStore: ResetProgressStore,
): WalletCoordinator {
    val sdkWallet = persistableWalletProvider.persistableWallet.hideResetWallet(resetProgressStore)
    return WalletCoordinator(
        context = context,
        persistableWallet = sdkWallet,
        accountName = context.getString(R.string.accounts_zashi),
        keySource = "zashi",
        isTorEnabled = isTorEnabledStorageProvider.observe(),
        isExchangeRateEnabled = isExchangeRateEnabledStorageProvider.observe(),
        isSyncBlocked = isSyncBlocked(context, sdkWallet, resetProgressStore),
    )
}

/**
 * Reset withdraws wallet input after draining admitted recovery. During a cold resume, keep it out
 * of the SDK from initialization. Reopen the cold flow after clearing to read the current store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<PersistableWallet?>.hideResetWallet(resetProgressStore: ResetProgressStore) =
    resetProgressStore.isSdkInputSuppressed
        .flatMapLatest { suppressed -> if (suppressed) flowOf(null) else this }
        .buffer(0)

/**
 * [OrchardMigrationSdk.new] needs the wallet's network and endpoint, which only exist once a
 * wallet has been created — reconstructed on every [PersistableWalletProvider.persistableWallet]
 * emission (the same reactive shape [WalletCoordinator] itself uses internally to rebuild its
 * `Synchronizer`), so a fresh install with no wallet yet just reports "not blocked" instead of
 * needing an account/network that don't exist. No specific account is selected yet at this point
 * either (this runs before any [cash.z.ecc.android.sdk.Synchronizer] — and so before the account
 * picker itself — exists), so `account = null`: [OrchardMigrationSdk.isSyncBlocked] then checks
 * every account in the wallet rather than assuming one.
 */
private fun isSyncBlocked(
    context: Context,
    sdkWallet: Flow<PersistableWallet?>,
    resetProgressStore: ResetProgressStore,
) =
    sdkWallet.observeSdkMigrationSyncBlock(resetProgressStore) { wallet ->
        OrchardMigrationSdk
            .new(
                appContext = context,
                zcashNetwork = wallet.network,
                lightWalletEndpoint = wallet.endpoint,
                account = null,
            ).isSyncBlocked()
    }

@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<PersistableWallet?>.observeSdkMigrationSyncBlock(
    resetProgressStore: ResetProgressStore,
    observe: (PersistableWallet) -> Flow<Boolean>,
): Flow<Boolean> =
    flatMapLatest { wallet ->
        if (wallet == null) flowOf(false) else resetProgressStore.observeMigrationSyncBlock { observe(wallet) }
    }
