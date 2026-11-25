package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavDestination.Companion.hasRoute
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKException
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import com.sparrowwallet.hummingbird.UR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ParseKeystoneSignInRequestUseCase(
    private val navigationRouter: NavigationRouter,
    keystoneSDKProvider: KeystoneSDKProvider,
    private val session: KeystoneEnrollmentSession,
) : BaseKeystoneScanner(keystoneSDKProvider) {
    private var sessionId: String? = null
    private var presentation: Presentation? = null
    private var isClosed = false

    internal fun beginPresentation(): String =
        UUID.randomUUID().toString().also { presentation = Presentation(it, session.captureWallet()) }

    internal fun isCurrentPresentation(id: String): Boolean = !isClosed && presentation?.id == id

    internal fun endPresentation(id: String) {
        if (presentation?.id == id) presentation = null
    }

    override suspend fun onSuccess(ur: UR, scanSessionId: String?) {
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
                if (scanSessionId == null || !isCurrentPresentation(scanSessionId) ||
                    entry?.destination?.hasRoute<ScanKeystoneSignInRequest>() != true
                ) {
                    null
                } else {
                    val owner = presentation?.owner ?: return@custom null
                    val id = session.start(accounts, owner) ?: return@custom null
                    sessionId = id
                    NavigationCommand.Forward(listOf(SelectKeystoneAccount(id)))
                }
            }
        }
    }

    internal fun close() {
        isClosed = true
        presentation = null
        sessionId?.let(session::clear)
        sessionId = null
    }

    private data class Presentation(val id: String, val owner: WalletEnrollmentToken?)
}

class InvalidKeystoneSignInQRException(
    cause: Throwable
) : Exception(cause)
