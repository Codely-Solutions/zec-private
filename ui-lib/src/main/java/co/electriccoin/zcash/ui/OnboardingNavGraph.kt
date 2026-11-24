package co.electriccoin.zcash.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import androidx.navigation.toRoute
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.spackle.FirebaseTestLabUtil
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.screen.error.AndroidErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.AndroidErrorDialog
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.onboarding.Onboarding
import co.electriccoin.zcash.ui.screen.onboarding.persistExistingWalletWithSeedPhrase
import co.electriccoin.zcash.ui.screen.onboarding.view.Onboarding
import co.electriccoin.zcash.ui.screen.restore.date.RestoreDateArgs
import co.electriccoin.zcash.ui.screen.restore.date.RestoreDateScreen
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreEstimationArgs
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreEstimationScreen
import co.electriccoin.zcash.ui.screen.restore.height.AndroidRestoreHeight
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeight
import co.electriccoin.zcash.ui.screen.restore.info.AndroidSeedInfo
import co.electriccoin.zcash.ui.screen.restore.info.SeedInfo
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedScreen
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedVM
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorArgs
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorScreen
import co.electriccoin.zcash.ui.screen.scan.thirdparty.AndroidThirdPartyScan
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel

fun NavGraphBuilder.onboardingNavGraph(
    activity: ComponentActivity,
    navigationRouter: NavigationRouter,
    walletViewModel: WalletViewModel
) {
    navigation<OnboardingGraph>(
        startDestination = Onboarding,
    ) {
        composable<Onboarding> {
            val initialization by walletViewModel.walletInitialization.collectAsStateWithLifecycle()
            Onboarding(
                initialization = initialization,
                onContinueWallet = walletViewModel::continueWalletSetup,
                onRetrySetup = walletViewModel::retryWalletInitialization,
                onImportWallet = {
                    if (FirebaseTestLabUtil.isFirebaseTestLab(activity.applicationContext)) {
                        persistExistingWalletWithSeedPhrase(
                            activity.applicationContext,
                            walletViewModel,
                            SeedPhrase.Companion.new(WalletFixture.Alice.seedPhrase),
                            WalletFixture.Alice
                                .getBirthday(ZcashNetwork.Companion.fromResources(activity.applicationContext))
                        )
                    } else {
                        navigationRouter.forward(RestoreSeedArgs)
                    }
                },
                onCreateWallet = {
                    if (FirebaseTestLabUtil.isFirebaseTestLab(activity.applicationContext)) {
                        persistExistingWalletWithSeedPhrase(
                            activity.applicationContext,
                            walletViewModel,
                            SeedPhrase.Companion.new(WalletFixture.Alice.seedPhrase),
                            WalletFixture.Alice.getBirthday(
                                ZcashNetwork.Companion.fromResources(
                                    activity.applicationContext
                                )
                            )
                        )
                    } else {
                        walletViewModel.createNewWallet()
                    }
                }
            )
        }
        restoreScreens()
        composable<ThirdPartyScan> { AndroidThirdPartyScan() }
        dialogComposable<ErrorDialog> { AndroidErrorDialog(it.toRoute()) }
        dialogComposable<ErrorBottomSheet> { AndroidErrorBottomSheet(it.toRoute()) }
    }
}

private fun NavGraphBuilder.restoreScreens() {
    composable<RestoreSeedArgs> { RestoreSeedScreen() }
    composable<RestoreHeight> { entry ->
        val args = entry.toRoute<RestoreHeight>()
        WithRestoreSession(entry, args.sessionId) { seedEntry ->
            AndroidRestoreHeight(args) { seedEntry.restoreSession.clear(args.sessionId) }
        }
    }
    composable<RestoreDateArgs> { entry ->
        val args = entry.toRoute<RestoreDateArgs>()
        WithRestoreSession(entry, args.sessionId) { RestoreDateScreen(args) }
    }
    composable<RestoreEstimationArgs> { entry ->
        val args = entry.toRoute<RestoreEstimationArgs>()
        WithRestoreSession(entry, args.sessionId) { RestoreEstimationScreen(args) }
    }
    dialogComposable<SeedInfo> { AndroidSeedInfo() }
    dialogComposable<RestoreTorArgs> { entry ->
        val args = entry.toRoute<RestoreTorArgs>()
        WithRestoreSession(entry, args.sessionId) { seedEntry -> RestoreTorScreen(args, seedEntry) }
    }
}

@Composable
private fun WithRestoreSession(
    entry: NavBackStackEntry,
    sessionId: String,
    content: @Composable (RestoreSeedVM) -> Unit
) {
    val navController = LocalNavController.current
    val owner = navController.previousRestoreSeedEntry()
    val seedEntry = owner?.let { koinViewModel<RestoreSeedVM>(viewModelStoreOwner = it) }
    if (seedEntry?.restoreSession?.resolve(sessionId) != null) {
        content(seedEntry)
    } else {
        LaunchedEffect(entry) {
            navController.currentBackStackEntryFlow.first { current ->
                current == entry ||
                    (current.destination.hasRoute<SeedInfo>() && navController.previousBackStackEntry == entry)
            }
            navController.returnToRestoreSeed()
        }
    }
}

private fun NavHostController.previousRestoreSeedEntry(): NavBackStackEntry? =
    try {
        getBackStackEntry(RestoreSeedArgs)
    } catch (_: IllegalArgumentException) {
        null
    }

internal fun NavHostController.returnToRestoreSeed() {
    if (!popBackStack(RestoreSeedArgs, inclusive = false)) {
        navigate(RestoreSeedArgs) { popUpTo<Onboarding>() }
    }
}
