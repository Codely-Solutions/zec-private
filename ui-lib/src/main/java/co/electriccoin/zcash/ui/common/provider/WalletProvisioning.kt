package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.zcash.global.WalletSdkTransportGate

/** Retires the durable setup intent only after the same seed has its first native software account. */
class WalletProvisioning(
    private val wallets: PersistableWalletProviderImpl,
    private val transport: WalletSdkTransportGate,
) {
    internal suspend fun confirm(
        engine: Synchronizer,
        accounts: List<Account>,
        keySource: String,
        isCurrentEngine: () -> Boolean,
        deriveFirstFullViewingKey: suspend (PersistableWallet) -> String = ::deriveFirstFullViewingKey,
    ) {
        fun current() = isCurrentEngine() && !transport.inputSuspended
        if (!current()) return
        val primary = accounts.firstOrNull { it.keySource == keySource && it.hdAccountIndex?.index == 0L }
        val storedKey = primary?.ufvk ?: return
        val version = wallets.writeVersion
        val wallet = wallets.getPersistableWallet()
        if (wallet != null && wallet.walletInitMode != WalletInitMode.ExistingWallet &&
            wallet.network == engine.network &&
            storedKey == deriveFirstFullViewingKey(wallet)
        ) {
            wallets.markProvisioned(wallet, version, ::current)
        }
    }
}

private suspend fun deriveFirstFullViewingKey(wallet: PersistableWallet): String {
    val seed = wallet.seedPhrase.toByteArray()
    return try {
        DerivationTool.getInstance().deriveUnifiedFullViewingKeys(seed, wallet.network, 1).single().encoding
    } finally {
        seed.fill(0)
    }
}
