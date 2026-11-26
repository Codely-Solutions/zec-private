package co.electriccoin.zcash.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.toRoute
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.LocalKeyboardManager
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.enterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.exitTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popEnterTransition
import co.electriccoin.zcash.ui.design.animation.ScreenAnimation.popExitTransition
import co.electriccoin.zcash.ui.design.theme.LocalAppearanceMode
import co.electriccoin.zcash.ui.design.theme.LocalOledEnabled
import co.electriccoin.zcash.ui.design.util.LocalNavController
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiScreen
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.StoredWalletUnavailableView
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressArgs
import co.electriccoin.zcash.ui.screen.swap.refundwarning.SwapRefundWarningArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.BoundTransactionProgressArgs
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import co.electriccoin.zcash.ui.screen.tor.settings.TorRestartRequiredView
import co.electriccoin.zcash.ui.util.SettingsUtil
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun RootNavGraph(
    secretState: SecretState,
    walletViewModel: WalletViewModel,
    storageCheckViewModel: StorageCheckViewModel = koinViewModel(),
    hasAppAccess: () -> Boolean,
) {
    val resetProgress = koinInject<ResetProgressStore>()
    val isResetPending by resetProgress.isPending.collectAsStateWithLifecycle()
    val transportGate = koinInject<WalletSdkTransportGate>()
    val restartForTor by transportGate.restartRequired.collectAsStateWithLifecycle()
    when (walletRootSurface(secretState, isResetPending, restartForTor)) {
        WalletRootSurface.RESET -> ResetZashiScreen()
        WalletRootSurface.TOR_RESTART -> {
            val activity = LocalActivity.current
            TorRestartRequiredView { activity.startActivity(SettingsUtil.newSettingsIntent(activity.packageName)) }
        }
        WalletRootSurface.WAITING -> CircularScreenProgressIndicator()
        WalletRootSurface.RECOVERY -> UnavailableWalletRecovery(walletViewModel, resetProgress, hasAppAccess)
        WalletRootSurface.NAVIGATION -> WalletNavHost(secretState, walletViewModel, storageCheckViewModel, hasAppAccess)
    }
}

internal enum class WalletRootSurface { RESET, TOR_RESTART, WAITING, RECOVERY, NAVIGATION }

internal fun walletRootSurface(
    secretState: SecretState,
    isResetPending: Boolean,
    restartForTor: Boolean = false,
): WalletRootSurface =
    when {
        isResetPending -> WalletRootSurface.RESET
        restartForTor -> WalletRootSurface.TOR_RESTART
        else -> when (secretState) {
            SecretState.LOADING -> WalletRootSurface.WAITING
            SecretState.UNAVAILABLE -> WalletRootSurface.RECOVERY
            SecretState.READY, SecretState.NONE, SecretState.INCOMPLETE -> WalletRootSurface.NAVIGATION
        }
    }

@Composable
private fun WalletNavHost(
    secretState: SecretState,
    walletViewModel: WalletViewModel,
    storageCheckViewModel: StorageCheckViewModel,
    hasAppAccess: () -> Boolean,
) {
    val keyboardManager = LocalKeyboardManager.current
    val navigationRouter = koinInject<NavigationRouter>()
    val applicationStateProvider = koinInject<ApplicationStateProvider>()
    val migrationAppHooks = koinInject<MigrationAppHooks>()
    val launchNavigation = koinInject<LaunchNavigationRequests>()
    val navController = LocalNavController.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentAccess = rememberUpdatedState(hasAppAccess)
    val browserAppearance = rememberUpdatedState(LocalAppearanceMode.current to LocalOledEnabled.current)
    val navigator =
        remember(
            activity,
            lifecycle,
            navController,
            keyboardManager,
            applicationStateProvider
        ) {
            NavigatorImpl(
                activity = activity,
                navController = navController,
                keyboardManager = keyboardManager,
                applicationStateProvider = applicationStateProvider,
                browserAppearance = { browserAppearance.value },
                canCommitOwnedNavigation = {
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentAccess.value()
                }
            )
        }

    TrackActiveNavigationEntry(navController, navigationRouter)

    LaunchedEffect(navigationRouter) {
        navigationRouter.observePipeline().collect {
            if (!currentAccess.value()) return@collect
            when (it) {
                is CustomNavigationCommand -> navigator.executeCommand(it)
                is NavigationCommand -> navigator.executeCommand(it)
            }
        }
    }

    LaunchedEffect(navigationRouter, navigator, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            navigationRouter.collectPendingForwards(navigator) {
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && currentAccess.value()
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
            (secretState == SecretState.NONE || secretState == SecretState.INCOMPLETE) &&
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

    CollectLaunchRequests(secretState, navController, launchNavigation, navigator, currentAccess.value)
}

@Composable
private fun UnavailableWalletRecovery(
    walletViewModel: WalletViewModel,
    resetProgress: ResetProgressStore,
    hasAppAccess: () -> Boolean,
) {
    val support = koinInject<SendEmailUseCase>()
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val retryPending by walletViewModel.isWalletReadPending.collectAsStateWithLifecycle()
    val canAct = {
        hasAppAccess() && !resetProgress.isPending.value &&
            walletViewModel.secretState.value == SecretState.UNAVAILABLE &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    StoredWalletUnavailableView(
        onRetry = { if (canAct()) walletViewModel.retryWalletRead(canAct) },
        onSupport = { support.sendBlankSupport(canAct) },
        onBack = activity::finish,
        isRetrying = retryPending,
    )
}

@Composable
private fun CollectLaunchRequests(
    secretState: SecretState,
    navController: NavController,
    requests: LaunchNavigationRequests,
    navigator: NavigatorImpl,
    hasAppAccess: () -> Boolean,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appAccess = rememberUpdatedState(hasAppAccess)
    val errors = koinInject<NavigateToErrorUseCase>()
    LaunchedEffect(secretState, navController, requests, navigator, lifecycle, errors) {
        if (secretState == SecretState.LOADING) return@LaunchedEffect
        val walletReady = secretState == SecretState.READY
        val targetGraph = if (walletReady) MainAppGraph::class else OnboardingGraph::class
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            navController.currentBackStackEntryFlow.first {
                it.destination.parent?.route == targetGraph.qualifiedName
            }
            requests.deliverOnChanges(
                navController,
                walletReady,
                hasAppAccess = { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && appAccess.value() },
                navigate = navigator::executeLaunchCommand,
                errors = errors,
            )
        }
    }
}

internal suspend fun LaunchNavigationRequests.deliverOnChanges(
    navController: NavController,
    walletReady: Boolean,
    hasAppAccess: () -> Boolean,
    navigate: suspend (NavigationCommand, () -> Boolean) -> Boolean,
    errors: NavigateToErrorUseCase,
) {
    combine(changes, navController.currentBackStackEntryFlow) { _, _ -> Unit }.collect {
        deliver(
            walletReady,
            hasAppAccess = {
                val current = navController.currentErrorArgs(errors)
                hasAppAccess() && current != ErrorArgs.SeedRecoveryFailed
            },
            canPresentRecovery = { navController.currentBackStackEntry?.destination?.hasRoute<HomeArgs>() == true },
            navigate = navigate,
        )
    }
}

private fun NavController.currentErrorArgs(errors: NavigateToErrorUseCase): ErrorArgs? =
    currentBackStackEntry
        ?.takeIf { it.destination.hasRoute<ErrorDialog>() }
        ?.toRoute<ErrorDialog>()
        ?.let(errors::argsFor)

@Composable
private fun TrackActiveNavigationEntry(navController: NavController, navigationRouter: NavigationRouter) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(navController, navigationRouter, lifecycle) {
        val stopTracking = navController.trackActiveEntry(navigationRouter, lifecycle)
        onDispose(stopTracking)
    }
}

internal fun NavController.trackActiveEntry(
    navigationRouter: NavigationRouter,
    lifecycle: Lifecycle? = null,
): () -> Unit {
    val observer = navigationRouter.registerEntryObserver()
    var contentEntryId: String? = null
    fun update() {
        val entry = currentBackStackEntry
        if (entry?.destination !is FloatingWindow) contentEntryId = entry?.id
        val resumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
        observer.update(entry?.toActiveNavigationEntry(contentEntryId)?.copy(hostResumed = resumed))
    }
    val listener = NavController.OnDestinationChangedListener { _, _, _ -> update() }
    val lifecycleObserver = LifecycleEventObserver { _, _ -> update() }
    addOnDestinationChangedListener(listener)
    lifecycle?.addObserver(lifecycleObserver)
    return {
        lifecycle?.removeObserver(lifecycleObserver)
        removeOnDestinationChangedListener(listener)
        observer.close()
    }
}

internal fun NavBackStackEntry.toActiveNavigationEntry(contentEntryId: String? = id) = ActiveNavigationEntry(
    contentEntryId = contentEntryId,
    id = id,
    route = destination.route,
    refundWarningRequestId = if (destination.hasRoute<SwapRefundWarningArgs>()) {
        toRoute<SwapRefundWarningArgs>().requestId
    } else null,
    genericScanRequestId = if (destination.hasRoute<ScanGenericAddressArgs>()) {
        toRoute<ScanGenericAddressArgs>().requestId
    } else null,
    submissionProgressId = if (destination.hasRoute<BoundTransactionProgressArgs>()) {
        toRoute<BoundTransactionProgressArgs>().submissionId
    } else null,
)

@Serializable
data object OnboardingGraph

@Serializable
data object MainAppGraph
