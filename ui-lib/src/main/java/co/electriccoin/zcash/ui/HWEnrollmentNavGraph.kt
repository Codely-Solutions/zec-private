package co.electriccoin.zcash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollmentNavType
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.connecthw.date.HWDateArgs
import co.electriccoin.zcash.ui.screen.connecthw.date.HWFirstTransactionScreen
import co.electriccoin.zcash.ui.screen.connecthw.estimation.HWEstimationArgs
import co.electriccoin.zcash.ui.screen.connecthw.estimation.HWFirstTransactionEstimationScreen
import co.electriccoin.zcash.ui.screen.connecthw.height.HWHeightArgs
import co.electriccoin.zcash.ui.screen.connecthw.height.HWWBHScreen
import co.electriccoin.zcash.ui.screen.connecthw.neworactive.HWNewOrActiveArgs
import co.electriccoin.zcash.ui.screen.connecthw.neworactive.HWNewOrActiveScreen
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.scankeystone.WrapScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.AndroidSelectKeystoneAccount
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

internal fun NavGraphBuilder.hardwareEnrollmentScreens() {
    composable<ScanKeystoneSignInRequest> { WrapScanKeystoneSignInRequest() }
    composable<SelectKeystoneAccount> { entry ->
        val args = entry.toRoute<SelectKeystoneAccount>()
        WithPendingEnrollment(entry, args.sessionId) { AndroidSelectKeystoneAccount(args) }
    }
    composable<HWNewOrActiveArgs>(typeMap = HWWalletEnrollmentNavType.typeMap) { entry ->
        val args = entry.toRoute<HWNewOrActiveArgs>()
        WithPendingEnrollment(entry, args.enrollment.keystoneSessionId) { HWNewOrActiveScreen(args, entry.id) }
    }
    composable<HWDateArgs>(typeMap = HWWalletEnrollmentNavType.typeMap) { entry ->
        val args = entry.toRoute<HWDateArgs>()
        WithPendingEnrollment(entry, args.enrollment.keystoneSessionId) { HWFirstTransactionScreen(args, entry.id) }
    }
    composable<HWEstimationArgs>(typeMap = HWWalletEnrollmentNavType.typeMap) { entry ->
        val args = entry.toRoute<HWEstimationArgs>()
        WithPendingEnrollment(entry, args.enrollment.keystoneSessionId) {
            HWFirstTransactionEstimationScreen(args, entry.id)
        }
    }
    composable<HWHeightArgs>(typeMap = HWWalletEnrollmentNavType.typeMap) { entry ->
        val args = entry.toRoute<HWHeightArgs>()
        WithPendingEnrollment(entry, args.enrollment.keystoneSessionId) { HWWBHScreen(args, entry.id) }
    }
}

private val HWWalletEnrollment.keystoneSessionId: String?
    get() = (this as? HWWalletEnrollment.Keystone)?.sessionId

@Composable
private fun WithPendingEnrollment(entry: NavBackStackEntry, sessionId: String?, content: @Composable () -> Unit) {
    if (sessionId == null || koinInject<KeystoneEnrollmentSession>().resolve(sessionId) != null) {
        content()
    } else {
        val navController = LocalNavController.current
        LaunchedEffect(entry) {
            navController.currentBackStackEntryFlow.first { current ->
                current == entry ||
                    (current.destination is FloatingWindow && navController.previousBackStackEntry == entry)
            }
            navController.returnToKeystoneScanner()
        }
    }
}

internal fun NavHostController.returnToKeystoneScanner() {
    val hasScanner = try {
        getBackStackEntry(ScanKeystoneSignInRequest)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
    navigate(ScanKeystoneSignInRequest) {
        if (hasScanner) {
            popUpTo<ScanKeystoneSignInRequest> { inclusive = true }
        } else {
            popUpTo<HomeArgs>()
        }
    }
}
