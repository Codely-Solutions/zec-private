package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import com.keystone.module.ZcashAccount
import com.keystone.module.ZcashAccounts

class CreateKeystoneAccountUseCase(
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
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
    suspend operator fun invoke(
        accounts: ZcashAccounts,
        account: ZcashAccount,
        birthday: BlockHeight? = null,
    ) {
        val createdAccount =
            accountDataSource.importKeystoneAccount(
                ufvk = account.ufvk,
                seedFingerprint = accounts.seedFingerprint,
                index = account.index.toLong(),
                birthday = birthday,
            )
        accountDataSource.selectAccount(createdAccount)
        synchronizerProvider.resetSynchronizer()
    }
}
