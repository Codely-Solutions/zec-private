package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import com.keystone.module.ZcashAccount

class CreateKeystoneAccountUseCase(
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val walletEnrollment: WalletEnrollmentAccess,
) {
    /**
     * @throws InitializeException.ImportAccountCollisionException if the wallet already holds an
     *         account sharing a viewing key component with [account], e.g. a re-import of the same
     *         Keystone account; trying again cannot succeed.
     */
    @Throws(
        InitializeException.ImportAccountException::class,
        InitializeException.ImportAccountCollisionException::class,
    )
    internal suspend operator fun invoke(
        pending: KeystoneEnrollmentSession.Pending,
        account: ZcashAccount,
        birthday: BlockHeight? = null,
        isCurrent: () -> Boolean,
    ): KeystoneEnrollmentSession.Imported? = walletEnrollment.complete(pending.owner) { synchronizer ->
        if (!isCurrent()) return@complete null
        val imported = pending.imported.value ?: KeystoneEnrollmentSession.Imported(
            account = accountDataSource.importKeystoneAccount(
                synchronizer = synchronizer,
                ufvk = account.ufvk,
                seedFingerprint = pending.accounts.seedFingerprint,
                index = account.index.toLong(),
                birthday = birthday,
            ),
            birthday = birthday,
        ).also { pending.imported.value = it }
        val selected = walletEnrollment.write(pending.owner) {
            if (!isCurrent()) return@write false
            accountDataSource.selectAccount(imported.account)
            true
        } == true
        if (walletEnrollment.permits(pending.owner)) synchronizerProvider.resetSynchronizer()
        imported.takeIf { selected }
    }
}
