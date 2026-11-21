package co.electriccoin.zcash.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.provider.AppearanceModeStorageProvider
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.IsOledEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.GiftCardLinkStore
import co.electriccoin.zcash.ui.common.usecase.NavigateToRedeemGiftCardUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.LocalKeyboardManager
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.enterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.exitTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popEnterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popExitTransition
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiScreen
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Also opens the gift card scanner for a gift card link that arrived from outside the app. Such a link can arrive
 * before there is a wallet, or before the wallet graph is shown (cold start, onboarding); MainActivity only records
 * that one arrived (the link itself is discarded), and the scanner opens once the wallet graph is up, so the user
 * scans the card with the app.
 */
@Composable
fun RootNavGraph(
    secretState: SecretState,
    walletViewModel: WalletViewModel,
    storageCheckViewModel: StorageCheckViewModel = koinViewModel(),
    hasAppAccess: () -> Boolean,
) {
    val resetProgress = koinInject<ResetProgressStore>()
    val isResetPending by resetProgress.isPending.collectAsStateWithLifecycle()
    if (isResetPending) {
        ResetZashiScreen()
        return
    }

    val keyboardManager = LocalKeyboardManager.current
    val navigationRouter = koinInject<NavigationRouter>()
    val applicationStateProvider = koinInject<ApplicationStateProvider>()
    val appearanceModeStorageProvider = koinInject<AppearanceModeStorageProvider>()
    val isOledEnabledStorageProvider = koinInject<IsOledEnabledStorageProvider>()
    val migrationAppHooks = koinInject<MigrationAppHooks>()
    val giftCardLinkStore = koinInject<GiftCardLinkStore>()
    val navigateToRedeemGiftCard = koinInject<NavigateToRedeemGiftCardUseCase>()
    val navController = LocalNavController.current
    val activity = LocalActivity.current
    val currentAccess = rememberUpdatedState(hasAppAccess)
    val navigator: Navigator =
        remember(
            activity,
            navController,
            keyboardManager,
            applicationStateProvider,
            appearanceModeStorageProvider,
            isOledEnabledStorageProvider
        ) {
            NavigatorImpl(
                activity = activity,
                navController = navController,
                keyboardManager = keyboardManager,
                applicationStateProvider = applicationStateProvider,
                appearanceModeStorageProvider = appearanceModeStorageProvider,
                isOledEnabledStorageProvider = isOledEnabledStorageProvider
            )
        }

    LaunchedEffect(navigationRouter) {
        navigationRouter.observePipeline().collect {
            if (!currentAccess.value()) return@collect
            when (it) {
                is CustomNavigationCommand -> navigator.executeCommand(it)
                is NavigationCommand -> navigator.executeCommand(it)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = OnboardingGraph,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { enterTransition() },
        exitTransition = { exitTransition() },
        popEnterTransition = { popEnterTransition() },
        popExitTransition = { popExitTransition() }
    ) {
        this.onboardingNavGraph(
            activity = activity,
            navigationRouter = navigationRouter,
            walletViewModel = walletViewModel
        )

        this.walletNavGraph(
            storageCheckViewModel = storageCheckViewModel,
            walletViewModel = walletViewModel,
            navigationRouter = navigationRouter
        )
    }

    LaunchedEffect(secretState, navController) {
        if (!currentAccess.value()) return@LaunchedEffect
        if (secretState == SecretState.READY &&
            navController.currentDestination?.parent?.route != MainAppGraph::class.qualifiedName
        ) {
            keyboardManager.close()
            navController.navigate(MainAppGraph) {
                popUpTo(OnboardingGraph) {
                    inclusive = true
                }
            }
        } else if (
            secretState == SecretState.NONE &&
            navController.currentDestination?.parent?.route != OnboardingGraph::class.qualifiedName
        ) {
            keyboardManager.close()
            navController.navigate(OnboardingGraph) {
                popUpTo(MainAppGraph) {
                    inclusive = true
                }
            }
        }
        if (secretState == SecretState.READY) migrationAppHooks.checkRecovery()
    }

    LaunchedEffect(secretState, navController) {
        if (secretState != SecretState.READY) return@LaunchedEffect
        navController.currentBackStackEntryFlow.first {
            it.destination.parent?.route == MainAppGraph::class.qualifiedName
        }
        giftCardLinkStore.isInAppScanRequested.filter { it }.collect {
            if (currentAccess.value()) navigateToRedeemGiftCard.openRequestedInAppScan()
        }
    }
}

@Serializable
data object OnboardingGraph

@Serializable
data object MainAppGraph
