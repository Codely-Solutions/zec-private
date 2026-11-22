package co.electriccoin.zcash.ui.screen.advancedsettings.debug

import android.os.Process
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toEntropy
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SimulateSeedNotRelevantUseCase(
    private val persistableWalletProvider: PersistableWalletProvider,
) {
    suspend operator fun invoke() {
        val version = persistableWalletProvider.writeVersion
        val wallet = persistableWalletProvider.getPersistableWallet() ?: return
        val fakeSeedPhrase =
            withContext(Dispatchers.IO) {
                SeedPhrase(
                    Mnemonics
                        .MnemonicCode(
                            Mnemonics.WordCount.COUNT_24.toEntropy()
                        ).words
                        .map { it.concatToString() }
                )
            }
        if (persistableWalletProvider.storeIfCurrent(wallet, wallet.copy(seedPhrase = fakeSeedPhrase), version)) {
            Process.killProcess(Process.myPid())
        }
    }
}
