package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavDestination.Companion.hasRoute
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKException
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ParseKeystoneSignInRequestUseCase(
    private val navigationRouter: NavigationRouter,
    keystoneSDKProvider: KeystoneSDKProvider,
    private val session: KeystoneEnrollmentSession,
) : BaseKeystoneScanner(keystoneSDKProvider) {
    private var sessionId: String? = null
    private var isClosed = false

    override suspend fun onSuccess(ur: UR) {
        val accounts =
            try {
                keystoneSDKProvider.parseZcashAccounts(ur)
            } catch (e: KeystoneSDKException) {
                throw InvalidKeystoneSignInQRException(e)
            }
        if (accounts.accounts.isEmpty()) {
            throw InvalidKeystoneSignInQRException(IllegalArgumentException("No Keystone accounts in export"))
        }
        withContext(Dispatchers.Main.immediate) {
            navigationRouter.custom { entry ->
                if (isClosed || entry?.destination?.hasRoute<ScanKeystoneSignInRequest>() != true) {
                    null
                } else {
                    val id = session.start(accounts)
                    sessionId = id
                    NavigationCommand.Forward(listOf(SelectKeystoneAccount(id)))
                }
            }
        }
    }

    internal fun close() {
        isClosed = true
        sessionId?.let(session::clear)
        sessionId = null
    }
}

class InvalidKeystoneSignInQRException(
    cause: Throwable
) : Exception(cause)
