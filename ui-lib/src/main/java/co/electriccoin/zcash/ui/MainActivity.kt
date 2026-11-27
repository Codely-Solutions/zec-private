@file:Suppress("DEPRECATION")
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package co.electriccoin.zcash.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.compose.BindCompLocalProvider
import co.electriccoin.zcash.ui.common.compose.DisableScreenTimeout
import co.electriccoin.zcash.ui.common.extension.setContentCompat
import co.electriccoin.zcash.ui.common.migration.MigrationAppHooks
import co.electriccoin.zcash.ui.common.usecase.HandleExternalLinkUseCase
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationUIState
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.common.viewmodel.OldHomeViewModel
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.BlankSurface
import co.electriccoin.zcash.ui.design.component.ConfigurationOverride
import co.electriccoin.zcash.ui.design.component.Override
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.screen.ScreenTimeoutVM
import co.electriccoin.zcash.ui.screen.authentication.AppAccessBoundary
import co.electriccoin.zcash.ui.screen.authentication.AuthenticationUseCase
import co.electriccoin.zcash.ui.screen.authentication.RETRY_TRIGGER_DELAY
import co.electriccoin.zcash.ui.screen.authentication.WrapAuthentication
import co.electriccoin.zcash.ui.screen.authentication.view.AnimationConstants
import co.electriccoin.zcash.ui.screen.authentication.view.WelcomeAnimationAutostart
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.ui.screen.theme.ThemeVM
import co.electriccoin.zcash.ui.screen.warning.viewmodel.StorageCheckViewModel
import co.electriccoin.zcash.work.WorkIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions")
class MainActivity : FragmentActivity() {
    private val oldHomeViewModel by viewModel<OldHomeViewModel>()

    private val themeVM by viewModel<ThemeVM>()

    val walletViewModel by viewModel<WalletViewModel>()

    val storageCheckViewModel by viewModel<StorageCheckViewModel>()

    internal val authenticationViewModel by viewModel<AuthenticationViewModel>()

    lateinit var navControllerForTesting: NavHostController

    val configurationOverrideFlow = MutableStateFlow<ConfigurationOverride?>(null)

    private val migrationAppHooks: MigrationAppHooks by inject()
    private val handleExternalLink: HandleExternalLinkUseCase by inject()
    private val launchNavigation: LaunchNavigationRequests by inject()
    private val resetProgressStore: ResetProgressStore by inject()
    private val transportGate: WalletSdkTransportGate by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(restorableNavigationState(savedInstanceState))
        Twig.debug { "Activity state: Create" }
        val restoredInNewProcess = launchNavigation.restoreExternalScans(
            savedInstanceState?.getStringArrayList(PENDING_EXTERNAL_SCANS).orEmpty(),
        )
        val migrationNotice = if (restoredInNewProcess) {
            savedInstanceState?.getString(PENDING_MIGRATION_NOTICE)
        } else {
            launchNavigation.pendingMigrationLookup
        }
        migrationNotice?.let {
            migrationAppHooks.restoreNotificationProgress(it, lifecycleScope)
        }

        setAllowedScreenOrientation()

        setupSplashScreen()

        setupUiContent()

        monitorForBackgroundSync()

        handleViewIntent(intent, isRecreated = savedInstanceState != null)
        if (savedInstanceState == null) handleMigrationIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        stampNavigationState(outState)
        outState.putStringArrayList(PENDING_EXTERNAL_SCANS, launchNavigation.saveExternalScans())
        launchNavigation.migrationNoticeToSave?.let {
            outState.putString(PENDING_MIGRATION_NOTICE, it)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        handleViewIntent(intent, isRecreated = false)
        handleMigrationIntent(intent)
    }

    /**
     * Links from outside the app are never used as they are: `zcash:` URIs go to the third party scan screen and gift
     * card app links to the gift card scanner, both asking the user to scan the code with the app instead. The
     * recognized link is dropped from the intent after classification.
     */
    private fun handleViewIntent(
        intent: Intent,
        isRecreated: Boolean
    ) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        val isFromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        val consumed = handleExternalLink(data.scheme, data.host, isRedelivery = isRecreated || isFromHistory)
        if (consumed) {
            intent.data = null
        }
    }

    private fun handleMigrationIntent(intent: Intent) {
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            migrationAppHooks.handleIntent(intent, lifecycleScope)
        }
    }

    override fun onStart() {
        Twig.debug { "Activity state: Start" }
        authenticationViewModel.runAuthenticationRequiredCheck()
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        // Recover committed work even when Android resumes the already-started activity.
        lifecycleScope.launch { migrationAppHooks.checkRecovery() }
    }

    override fun onStop() {
        Twig.debug { "Activity state: Stop" }
        authenticationViewModel.onEnteredBackground()
        super.onStop()
    }

    /**
     * Sets whether the screen rotation is enabled or screen orientation is locked in the portrait mode.
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun setAllowedScreenOrientation() {
        requestedOrientation =
            if (BuildConfig.IS_SCREEN_ROTATION_ENABLED) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
    }

    /**
     * Holds the splash until the stored theme and wallet state are known, or a reset is pending. Without
     * the theme gate the first frames would render with [ThemeVM]'s seeded System/Classic Dark defaults and
     * then repaint the whole tree once the stored values land.
     *
     * The theme is the first operand because `||` short-circuits: reading it second would leave [ThemeVM]
     * unconstructed - and its preference read unstarted - until the wallet secret had already landed,
     * serialising the two reads instead of running them alongside each other.
     */
    private fun setupSplashScreen() {
        val splashScreen = installSplashScreen()
        val start = SystemClock.elapsedRealtime().milliseconds

        splashScreen.setKeepOnScreenCondition {
            if (SPLASH_SCREEN_DELAY > Duration.ZERO) {
                val now = SystemClock.elapsedRealtime().milliseconds

                // This delay is for debug purposes only; do not enable for production usage.
                if (now - start < SPLASH_SCREEN_DELAY) {
                    return@setKeepOnScreenCondition true
                }
            }

            !themeVM.isThemeResolved.value ||
                (!resetProgressStore.isPending.value && !transportGate.restartRequired.value &&
                    SecretState.LOADING == walletViewModel.secretState.value)
        }
    }

    private fun setupUiContent() {
        val appAccessSession = authenticationViewModel.appAccessSession
        // Turn off the decor fitting system windows, which allows us to handle insets,
        // including IME animations, and go edge-to-edge.
        // This also sets up the initial system bar style based on the platform theme
        enableEdgeToEdge()
        setContentCompat {
            Override(configurationOverrideFlow) {
                val isHideBalances by oldHomeViewModel.isHideBalances.collectAsStateWithLifecycle()
                val appearanceMode by themeVM.appearanceMode.collectAsStateWithLifecycle()
                val isOledEnabled by themeVM.isOledEnabled.collectAsStateWithLifecycle()
                ZcashTheme(
                    balancesAvailable = isHideBalances == false,
                    appearanceMode = appearanceMode,
                    isOledEnabled = isOledEnabled
                ) {
                    BlankSurface(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .imePadding()
                            // Maestro reads Compose testTags as resource-ids only
                            // when this flag is on at the root. File-level
                            // @OptIn(ExperimentalComposeUiApi::class) opts into the API.
                            .semantics { testTagsAsResourceId = true }
                    ) {
                        BindCompLocalProvider {
                            AppAccessBoundary(
                                session = appAccessSession,
                                lockedContent = { AuthenticationForAppAccess() }
                            ) { grant ->
                                MainContent { appAccessSession.permits(grant) }
                            }
                            ScreenTimeoutHandle()
                        }
                    }
                }
            }

            // Force collection to improve performance; sync can start happening while
            // the user is going through the backup flow.
            walletViewModel.synchronizer.collectAsStateWithLifecycle()
        }
    }

    @Composable
    private fun AuthenticationForAppAccess() {
        val authState = authenticationViewModel.appAccessAuthenticationResultState.collectAsStateWithLifecycle().value
        val animateAppAccess = authenticationViewModel.showWelcomeAnimation.collectAsStateWithLifecycle().value
        val authFailed = authenticationViewModel.authFailed.collectAsStateWithLifecycle().value

        if (animateAppAccess) {
            WelcomeAnimationAutostart(
                delay = AnimationConstants.INITIAL_DELAY.milliseconds,
                showAuthLogo = authFailed,
                onRetry = {
                    authenticationViewModel.resetAppAccessResult()
                    authenticationViewModel.authenticate(
                        activity = this,
                        initialAuthSystemWindowDelay = RETRY_TRIGGER_DELAY.milliseconds,
                        useCase = AuthenticationUseCase.AppAccess
                    )
                }
            )
        }

        when (authState) {
            AuthenticationUIState.Initial -> {
                Twig.debug { "Authentication initial state" }
                // Wait for the state update
            }

            AuthenticationUIState.NotRequired -> {
                Twig.debug { "App access authentication NOT required - welcome animation only" }
                // Wait until the welcome animation finishes then mark it was shown
                LaunchedEffect(key1 = authenticationViewModel.showWelcomeAnimation) {
                    delay(AnimationConstants.together())
                    authenticationViewModel.setWelcomeAnimationDisplayed()
                }
            }

            AuthenticationUIState.Required -> {
                Twig.debug { "App access authentication required" }

                // Check and trigger app access authentication if required
                // Note that the Welcome animation is part of its logic
                WrapAuthentication(
                    onSuccess = {
                        authenticationViewModel.appAccessAuthentication.value = AuthenticationUIState.Successful
                    },
                    onCancel = {
                        authenticationViewModel.setAuthFailed()
                    },
                    onFail = {
                        authenticationViewModel.setAuthFailed()
                    },
                    useCase = AuthenticationUseCase.AppAccess
                )
            }

            AuthenticationUIState.Successful -> {
                Twig.debug { "Authentication successful - entering the app" }
                // No action is needed - the main app content is laid out now
            }
        }
    }

    @Composable
    private fun MainContent(hasAppAccess: () -> Boolean) {
        val secretState by walletViewModel.secretState.collectAsStateWithLifecycle()
        RootNavGraph(secretState, walletViewModel, storageCheckViewModel, hasAppAccess)
    }

    @Composable
    private fun ScreenTimeoutHandle() {
        val vm = koinViewModel<ScreenTimeoutVM>()
        val isScreenTimeoutDisabled by vm.isScreenTimeoutDisabled.collectAsStateWithLifecycle()

        if (isScreenTimeoutDisabled == true) {
            DisableScreenTimeout()
        }
    }

    private fun monitorForBackgroundSync() {
        val isEnableBackgroundSyncFlow =
            run {
                val isSecretReadyFlow = walletViewModel.secretState.map { it == SecretState.READY }
                val isBackgroundSyncEnabledFlow = oldHomeViewModel.isBackgroundSyncEnabled.filterNotNull()

                combine(isSecretReadyFlow, isBackgroundSyncEnabledFlow, resetProgressStore.isPending) {
                    isSecretReady, isBackgroundSyncEnabled, resetPending ->
                    isSecretReady && isBackgroundSyncEnabled && !resetPending
                }
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                isEnableBackgroundSyncFlow.collect { isEnableBackgroundSync ->
                    if (isEnableBackgroundSync) {
                        WorkIds.enableBackgroundSynchronization(application)
                    } else {
                        WorkIds.disableBackgroundSynchronization(application)
                    }
                }
            }
        }
    }

    companion object {
        private const val NAVIGATION_STATE_VERSION_KEY = "wallet_navigation_state_version"
        private const val PENDING_EXTERNAL_SCANS = "pending_external_scans"
        private const val PENDING_MIGRATION_NOTICE = "pending_migration_notice"
        private const val NAVIGATION_STATE_VERSION = 18

        internal fun stampNavigationState(state: Bundle) {
            state.putInt(NAVIGATION_STATE_VERSION_KEY, NAVIGATION_STATE_VERSION)
        }

        // Discard saved stacks when their typed routes or saved payloads use an older format.
        internal fun restorableNavigationState(state: Bundle?): Bundle? =
            state?.takeIf { it.getInt(NAVIGATION_STATE_VERSION_KEY) == NAVIGATION_STATE_VERSION }

        @VisibleForTesting
        internal val SPLASH_SCREEN_DELAY = 0.seconds
    }
}
