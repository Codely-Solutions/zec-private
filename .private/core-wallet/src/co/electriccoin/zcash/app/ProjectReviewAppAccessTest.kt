package co.electriccoin.zcash.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.Window
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.Lifecycle
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.MainAppGraph
import co.electriccoin.zcash.ui.BiometricActivity
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.repository.WalletInitializationState
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.AuthenticationViewModel
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.home.HomeTags
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackup
import co.electriccoin.zcash.ui.screen.walletbackup.WalletBackupViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** Runs against a blank, PIN-secured emulator with the opt-in core-wallet instrumentation. */
class ProjectReviewAppAccessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val arguments = InstrumentationRegistry.getArguments()
    private val lockedTitle get() = context.getString(R.string.splash_authTitle)
    private val backupTitle get() = context.getString(R.string.recoveryPhraseDisplay_screenTitle).uppercase()
    private val revealLabel get() = context.getString(R.string.recoveryPhraseDisplay_reveal)
    private val hideLabel get() = context.getString(R.string.recoveryPhraseDisplay_hide)
    private val copyLabel get() = context.getString(R.string.recovery_copy_phrase)
    private val biometricCreations = AtomicInteger()
    private val biometricRequests = AtomicInteger()
    private var actualBiometrics: BiometricRepository? = null
    private var diagnosticMode = false
    private var diagnosticFallbackUsed = false
    private var revealNumber = 0
    private val lifecycleCallback = ActivityLifecycleCallback { activity, stage ->
        if (activity is BiometricActivity && stage == Stage.CREATED) biometricCreations.incrementAndGet()
    }

    @Before
    fun observeBiometricActivity() = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycleCallback)
        val koin = GlobalContext.get()
        val actual = koin.get<BiometricRepository>()
        actualBiometrics = actual
        val observer = object : BiometricRepository by actual {
            override suspend fun requestBiometrics(request: BiometricRequest) {
                val number = biometricRequests.incrementAndGet()
                report("biometric_request_$number")
                try {
                    actual.requestBiometrics(request)
                    report("biometric_request_${number}_approved")
                } catch (error: Exception) {
                    val outcome = when (error) {
                        is BiometricsCancelledException -> "cancelled"
                        is BiometricsFailureException -> "failed"
                        is kotlinx.coroutines.CancellationException -> "coroutine_cancelled"
                        else -> "error"
                    }
                    report("biometric_request_${number}_$outcome")
                    throw error
                }
            }
        }
        koin.declare<BiometricRepository>(observer, allowOverride = true)
    }

    @After
    fun stopObservingBiometricActivity() {
        onMain {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycleCallback)
            actualBiometrics?.let { actual ->
                val koin = GlobalContext.get()
                koin.declare<BiometricRepository>(actual, allowOverride = true)
                check(koin.get<BiometricRepository>() === actual)
            }
        }
    }

    @Test
    fun cancellationAndBackgroundRelock() = verifyBackgroundRelock(forceTimeout = false)

    /** Diagnostic only: exercise the real lifecycle with a backdated monotonic checkpoint. */
    @Test
    fun cancellationAndForcedTimeoutDiagnostic() = verifyBackgroundRelock(forceTimeout = true)

    @Test
    fun interruptedSetupRequiresCredentialBeforeContinue() = runBlocking {
        val pin = prepareSecuredGuest()
        val walletProvider = dependency<PersistableWalletProvider>()
        val preferences = dependency<StandardPreferenceProvider>()()
        val repository = dependency<WalletRepository>()
        check(walletProvider.getPersistableWallet() == null)
        check(StandardPreferenceKeys.ONBOARDING_STATE.getValue(preferences) == OnboardingState.NONE.toNumber())
        dependency<IsTorEnabledStorageProvider>().store(false)
        repository.markIronwoodAnnouncementShown()
        // Reconstruct the persisted shape of an interrupted setup before the first activity starts.
        walletProvider.store(
            PersistableWallet.new(
                application = context.applicationContext as Application,
                zcashNetwork = ZcashNetwork.Testnet,
                endpoint = dependency<LightWalletEndpointProvider>().getDefaultEndpoint(),
                walletInitMode = WalletInitMode.RestoreWallet
            )
        )
        withTimeout(UI_TIMEOUT_MILLIS) {
            repository.walletInitialization.first { it == WalletInitializationState.INCOMPLETE }
            repository.secretState.first { it == SecretState.INCOMPLETE }
        }
        val continueLabel = context.getString(R.string.wallet_initialization_continue)
        val activity = instrumentation.startActivitySync(launchIntent()) as MainActivity
        try {
            waitForCredentialEntry()
            check(!hasLabel(continueLabel) && !hasWalletAction())
            cancelCredentialEntry()
            waitForLabel(lockedTitle)
            check(!hasLabel(continueLabel) && !hasWalletAction())
            check(StandardPreferenceKeys.ONBOARDING_STATE.getValue(preferences) == OnboardingState.NONE.toNumber())
            report("interrupted_setup_cancelled")

            retryUnlock(pin)
            val continueWallet = waitForBounds("continue interrupted setup", requireClick = true) {
                matchesLabel(it, continueLabel)
            }
            reportState("interrupted_setup_authenticated", emptySet(), activity)
            check(!hasWalletAction())
            tap(continueWallet)
            withTimeout(WALLET_TIMEOUT_MILLIS) { repository.secretState.first { it == SecretState.READY } }
            try {
                // Continuation resumes RESTORING, whose full-screen KeepOpen instructions can cover Home.
                // Require actual credential authorization and wallet-graph entry rather than a covered Home row.
                waitFor("authenticated wallet graph") {
                    onMain {
                        val model = androidx.lifecycle.ViewModelProvider(activity)[AuthenticationViewModel::class.java]
                        val auth = AuthenticationViewModel::class.java.getDeclaredField("appAccessAuthenticationResultState")
                            .apply { isAccessible = true }.get(model) as StateFlow<*>
                        navigationRoutes(activity).second == MainAppGraph::class.qualifiedName &&
                            auth.value?.javaClass?.simpleName == "Successful"
                    } && !hasLabel(lockedTitle) && !hasLabel(continueLabel)
                }
            } finally {
                reportState("interrupted_setup_wallet_graph", emptySet(), activity)
            }
            report("interrupted_setup_continued")
        } finally {
            onMain { activity.finish() }
        }
    }

    private fun verifyBackgroundRelock(forceTimeout: Boolean) = runBlocking {
        diagnosticMode = forceTimeout
        val pin = prepareSecuredGuest()
        report("secured_guest_verified")
        val walletWords = createIsolatedWalletWithForegroundSetup()
        report("isolated_wallet_created")
        val activity = instrumentation.startActivitySync(launchIntent()) as MainActivity
        waitForCredentialEntry()
        assertWalletAbsent(walletWords)
        report("initial_credential_prompt")

        cancelCredentialEntry()
        try {
            waitForLabel(lockedTitle)
        } finally {
            reportState("initial_cancel_settled", walletWords, activity)
        }
        assertWalletAbsent(walletWords)
        report("initial_authentication_cancelled")
        // Use harmless routing while locked to check that the wallet command consumer is absent.
        dependency<NavigationRouter>().forward(WalletBackup(false))
        delay(400)
        assertWalletAbsent(walletWords)
        retryUnlock(pin)
        waitFor("authenticated wallet action") { hasWalletAction() }
        report("initial_app_access_unlocked")
        dependency<NavigationRouter>().forward(WalletBackup(false))
        waitForLabel(backupTitle)
        report("backup_screen_ready")
        revealBackup(pin, walletWords)
        report("reveal_before_background")

        shell("input keyevent ${KeyEvent.KEYCODE_HOME}")
        val applicationState = dependency<ApplicationStateProvider>()
        withTimeout(UI_TIMEOUT_MILLIS) { applicationState.isInForeground.first { !it } }
        val wentToBackground = SystemClock.elapsedRealtime()
        report("background_wait_started")
        if (forceTimeout) {
            check(arguments.getString("forcedTimeoutDiagnostic") == "backdated-checkpoint")
            onMain {
                val field = AuthenticationViewModel::class.java
                    .getDeclaredField("latestAppBackgroundedElapsedRealtimeMillis")
                    .apply { isAccessible = true }
                val model = androidx.lifecycle.ViewModelProvider(activity)[AuthenticationViewModel::class.java]
                val recorded = checkNotNull(field.get(model) as Long?)
                field.set(model, recorded - BACKGROUND_MILLIS)
            }
            report("diagnostic_checkpoint_backdated")
        } else {
            delay(BACKGROUND_MILLIS)
        }
        val elapsed = SystemClock.elapsedRealtime() - wentToBackground
        check(forceTimeout || elapsed >= BACKGROUND_MILLIS)

        val launcher = checkNotNull(launchIntent().component).flattenToShortString()
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $launcher")
        waitForCredentialEntry()
        assertWalletAbsent(walletWords)
        report("return_credential_prompt")
        cancelCredentialEntry()
        reportState("return_cancel_sent", walletWords, activity)
        try {
            waitForLabel(lockedTitle)
        } finally {
            reportState("return_cancel_settled", walletWords, activity)
        }
        assertWalletAbsent(walletWords)
        report("return_authentication_cancelled")
        check(onMain { !activity.isDestroyed }) { "Original activity was destroyed while backgrounded" }

        retryUnlock(pin)
        report("return_pin_entered")
        try {
            waitForLabel(backupTitle)
        } finally {
            reportState("return_unlock_settled", walletWords, activity)
        }
        scrollToTop()
        check(!hasLabel(copyLabel)) { "Recovery copy remained available after relock" }
        check(!hasWalletWord(walletWords)) { "Recovery words remained exposed after relock" }
        revealBackup(pin, walletWords)
        check(!diagnosticFallbackUsed) {
            "Coordinate input did not activate recovery; accessibility diagnostic is not timed acceptance"
        }
        report(if (forceTimeout) "diagnostic_passed" else "passed", elapsed)
    }

    private suspend fun prepareSecuredGuest(): String {
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet)
        check(arguments.getString("isolatedTestnet") == "valueless-coins-only")
        check(arguments.getString("securedGuest") == "disposable-emulator")
        val pin = checkNotNull(arguments.getString("fixturePin"))
        check(pin.matches(Regex("[0-9]{6}")))
        val keyguard = checkNotNull(context.getSystemService(KeyguardManager::class.java))
        check(keyguard.isDeviceSecure && !keyguard.isDeviceLocked) { "The secured test guest must be unlocked" }
        check(!dependency<GetVersionInfoProvider>()().isRunningUnderTestService)
        val preferences = dependency<StandardPreferenceProvider>()()
        check(StandardPreferenceKeys.IS_APP_ACCESS_AUTHENTICATION.getValue(preferences))
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return pin
    }

    private suspend fun createIsolatedWalletWithForegroundSetup(): Set<String> {
        val walletProvider = dependency<PersistableWalletProvider>()
        check(walletProvider.getPersistableWallet() == null) { "A fresh emulator wallet is required" }
        val activity = instrumentation.startActivitySync(launchIntent()) as MainActivity
        val repository = dependency<WalletRepository>()
        repository.markIronwoodAnnouncementShown()
        val createWalletLabel = context.getString(R.string.plainOnboarding_button_createNewWallet)
        val createWallet = waitForBounds("onboarding wallet creation", requireClick = true) {
            matchesLabel(it, createWalletLabel)
        }
        tap(createWallet)
        withTimeout(WALLET_TIMEOUT_MILLIS) { repository.secretState.first { it == SecretState.READY } }
        val wallet = checkNotNull(walletProvider.getPersistableWallet())
        check(wallet.network == ZcashNetwork.Testnet)
        onMain { activity.finish() }
        waitFor("wallet setup activity to finish") { onMain { activity.isDestroyed } }
        return wallet.seedPhrase.split.toSet()
    }

    private fun launchIntent(): Intent =
        checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun assertWalletAbsent(walletWords: Set<String>) {
        check(!hasLabel(backupTitle) && !hasLabel(copyLabel) && !hasWalletWord(walletWords))
        check(!hasWalletAction()) {
            "Wallet action was accessible while app access was locked"
        }
    }

    private fun hasWalletAction() = findBounds {
        it.packageName == context.packageName && it.viewIdResourceName?.substringAfterLast('/') in walletActionTags
    } != null

    private suspend fun retryUnlock(pin: String) {
        val description =
            context.getString(R.string.authentication_failed_welcome_icon_cont_desc, context.getString(R.string.app_name))
        val retry = waitForBounds("app unlock retry", requireClick = true) {
            it.contentDescription?.toString() == description
        }
        tap(retry)
        waitForCredentialEntry()
        enterPin(pin)
    }

    private suspend fun revealBackup(pin: String, walletWords: Set<String>) {
        scrollToReveal()
        revealNumber++
        val reveal = waitForBounds("recovery reveal action", requireClick = true) { matchesLabel(it, revealLabel) }
        val before = biometricCreations.get()
        val requestsBefore = biometricRequests.get()
        traceRevealTap(reveal)
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline &&
            biometricCreations.get() == before && !hasFocusedCredentialEntry()
        ) delay(POLL_MILLIS)
        android.util.Log.i("ProjectReviewAppAccess", JSONObject()
            .put("phase", "reveal_physical_tap")
            .put("bounds", reveal.toShortString())
            .put("display_height", context.resources.displayMetrics.heightPixels)
            .put("ime_visible", automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
            .put("biometric_activity_created", biometricCreations.get() > before)
            .put("biometric_requests", biometricRequests.get() - requestsBefore)
            .put("credential_focused", hasFocusedCredentialEntry())
            .toString())
        if (diagnosticMode && biometricCreations.get() == before && !hasFocusedCredentialEntry()) {
            diagnosticFallbackUsed = true
            check(!hasWalletWord(walletWords) && !hasLabel(copyLabel))
            if (arguments.getString("maskedFrames") == "allow-only-masked-recovery") {
                captureMaskedRecovery(walletWords)
            }
            val accepted = findBounds(requireClick = true, performClick = true) { matchesLabel(it, revealLabel) } != null
            android.util.Log.i("ProjectReviewAppAccess", JSONObject()
                .put("phase", "diagnostic_reveal_accessibility_click").put("accepted", accepted).toString())
        }
        waitForCredentialEntry()
        enterPin(pin)
        waitFor("authorized recovery reveal") { hasLabel(hideLabel) || hasLabel(copyLabel) }
        scrollToTop()
        waitFor("revealed recovery phrase and copy control") { hasWalletWord(walletWords) && hasLabel(copyLabel) }
    }

    private fun traceRevealTap(bounds: Rect) {
        val activity = onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        onMain {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val compose = content.getChildAt(0)
            val visible = Rect()
            val isVisible = compose.getGlobalVisibleRect(visible)
            val geometry = JSONObject().put("phase", "reveal_view_geometry").put("reveal", revealNumber)
                .put("content_height", content.height).put("compose_height", compose.height)
                .put("compose_width", compose.width).put("compose_visible", isVisible)
                .put("compose_visible_bounds", visible.toShortString())
                .put("decor_height", activity.window.decorView.height)
                .put("window_flags", activity.window.attributes.flags)
            if (Build.VERSION.SDK_INT >= 30) {
                geometry.put("ime_visible", compose.rootWindowInsets?.isVisible(WindowInsets.Type.ime()))
                    .put("ime_bottom", compose.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom)
            }
            android.util.Log.i("ProjectReviewAppAccess", geometry.toString())
        }
        val original = onMain { checkNotNull(activity.window.callback) }
        val observer = object : Window.Callback by original {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val handled = original.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                    android.util.Log.i("ProjectReviewAppAccess", JSONObject()
                        .put("phase", "reveal_touch_dispatch").put("reveal", revealNumber)
                        .put("action", event.actionMasked).put("flags", event.flags)
                        .put("gesture_elapsed_ms", event.eventTime - event.downTime)
                        .put("handled", handled).toString())
                }
                return handled
            }
        }
        onMain { activity.window.callback = observer }
        try {
            tap(bounds)
        } finally {
            onMain {
                if (activity.window.callback === observer) activity.window.callback = original
                check(activity.window.callback === original) { "Unexpected concurrent window callback change" }
            }
        }
    }

    private suspend fun captureMaskedRecovery(words: Set<String>) {
        val activity = onMain {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        fun assertMasked() = onMain {
            // Keep the app's implementation-only Navigation dependency off the instrumentation classpath.
            val controller = MainActivity::class.java.getMethod("getNavControllerForTesting").invoke(activity)
            val entry = checkNotNull(controller.javaClass.getMethod("getCurrentBackStackEntry").invoke(controller))
                as androidx.lifecycle.ViewModelStoreOwner
            val model = androidx.lifecycle.ViewModelProvider(entry)[WalletBackupViewModel::class.java]
            val state = checkNotNull(model.state.value)
            check(!state.seed.isRevealed && state.copyPhrase == null)
        }
        assertMasked()
        check(!hasWalletWord(words) && !hasLabel(copyLabel))
        val originalFlags = onMain { activity.window.attributes.flags }
        try {
            onMain { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            delay(750)
            assertMasked()
            check(!hasWalletWord(words) && !hasLabel(copyLabel))
            automation.takeScreenshot()?.let { bitmap ->
                try {
                    File(context.cacheDir, "masked-recovery-$revealNumber.png").outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            onMain {
                if (originalFlags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE ==
                    originalFlags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    private suspend fun scrollToReveal() {
        repeat(MAX_SCROLLS) {
            if (findBounds(requireClick = true) { matchesLabel(it, revealLabel) } != null) return
            check(scrollRecovery(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "Reveal is not reachable by scrolling" }
            delay(POLL_MILLIS)
        }
    }

    private suspend fun scrollToTop() {
        repeat(MAX_SCROLLS) {
            if (!scrollRecovery(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            delay(POLL_MILLIS)
        }
    }

    @Suppress("DEPRECATION")
    private fun scrollRecovery(action: Int): Boolean {
        fun scroll(node: AccessibilityNodeInfo): Boolean = try {
            if (node.packageName == context.packageName && node.isVisibleToUser && node.isScrollable &&
                node.actionList.any { it.id == action }
            ) {
                node.performAction(action)
            } else {
                (0 until node.childCount).any { index -> node.getChild(index)?.let(::scroll) == true }
            }
        } finally { node.recycle() }
        val moved = automation.windows.any { it.root?.let(::scroll) == true }
        if (moved) automation.waitForIdle(500, 5_000)
        return moved
    }

    private suspend fun waitForCredentialEntry() {
        waitFor("system PIN entry") { hasFocusedCredentialEntry() }
        // A visible node during the credential Activity's entry transition cannot yet receive Back/PIN.
        automation.waitForIdle(250, 5_000)
    }

    private fun hasFocusedCredentialEntry(): Boolean = focusedCredentialWindowId() != null

    private fun focusedCredentialWindowId(): Int? =
        automation.windows.firstNotNullOfOrNull { window ->
            val credential = window.isActive && window.isFocused && window.root?.let { root ->
                try {
                    root.packageName != context.packageName && containsCredentialEntry(root)
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            } == true
            window.id.takeIf { credential }
        }

    private fun containsCredentialEntry(node: AccessibilityNodeInfo): Boolean {
        if (node.isVisibleToUser &&
            (node.isPassword || node.viewIdResourceName?.endsWith(":id/pinEntry") == true)
        ) return true
        return (0 until node.childCount).any { index ->
            node.getChild(index)?.let { child ->
                try {
                    containsCredentialEntry(child)
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            } == true
        }
    }

    private fun enterPin(pin: String) {
        shell("input text $pin")
        shell("input keyevent ${KeyEvent.KEYCODE_ENTER}")
    }

    private fun pressBack() = shell("input keyevent ${KeyEvent.KEYCODE_BACK}")

    private suspend fun cancelCredentialEntry() {
        val credentialWindow = checkNotNull(focusedCredentialWindowId())
        val imeBefore = automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        pressBack()
        delay(750)
        // Back may first dismiss the credential screen's IME. Cancel the credential Activity itself too.
        if (focusedCredentialWindowId() == credentialWindow &&
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false
        ) {
            android.util.Log.i("ProjectReviewAppAccess", JSONObject()
                .put("phase", "credential_window_remained_after_back")
                .put("same_window", true)
                .put("ime_before", imeBefore)
                .put("ime_after", automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD })
                .toString())
            pressBack()
        }
    }

    private fun tap(bounds: Rect) {
        // Android's `input tap` gives DOWN and UP the same event time. Use a real short press instead.
        val x = bounds.centerX()
        val y = bounds.centerY()
        shell("input touchscreen swipe $x $y $x $y 100")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { input ->
            val buffer = ByteArray(1024)
            while (input.read(buffer) != -1) {
                // Shell output is drained inside the guest.
            }
        }
    }

    private suspend fun waitForLabel(label: String) {
        waitFor("app label: $label") { hasLabel(label) }
    }

    private fun hasLabel(label: String) = findBounds { matchesLabel(it, label) } != null

    private fun matchesLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        node.packageName == context.packageName &&
            (node.text?.toString() == label || node.contentDescription?.toString() == label)

    private fun hasWalletWord(words: Set<String>) = findBounds {
        it.packageName == context.packageName && it.text?.toString() in words
    } != null

    private suspend fun waitForBounds(
        label: String,
        requireClick: Boolean = false,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): Rect {
        var result: Rect? = null
        waitFor(label) {
            result = findBounds(requireClick = requireClick, match = match)
            result != null
        }
        return checkNotNull(result)
    }

    private suspend fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            delay(POLL_MILLIS)
        }
        error("Timed out waiting for $label")
    }

    @Suppress("DEPRECATION")
    private fun findBounds(
        requireClick: Boolean = false,
        performClick: Boolean = false,
        match: (AccessibilityNodeInfo) -> Boolean
    ): Rect? {
        fun clickBounds(node: AccessibilityNodeInfo): Rect? {
            val labelBounds = Rect().also(node::getBoundsInScreen)
            var control: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
            try {
                repeat(4) {
                    val current = control ?: return null
                    if (!current.isEnabled || current.packageName != context.packageName) return null
                    if (current.isClickable) {
                        val bounds = Rect().also(current::getBoundsInScreen)
                        val density = context.resources.displayMetrics.density
                        val bounded = bounds.height() >= 24 * density &&
                            bounds.height() <= 192 * density && bounds.contains(labelBounds)
                        val clickAction = current.actionList.any {
                            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id
                        }
                        return bounds.takeIf {
                            current.isVisibleToUser && bounded && clickAction &&
                                (!performClick || current.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        }
                    }
                    control = current.parent
                    current.recycle()
                }
            } finally {
                control?.recycle()
            }
            return null
        }

        fun search(node: AccessibilityNodeInfo): Rect? = try {
            if (node.isVisibleToUser && match(node)) {
                if (requireClick) clickBounds(node) else Rect().also(node::getBoundsInScreen).takeUnless(Rect::isEmpty)
            } else {
                (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::search) }
            }
        } finally {
            node.recycle()
        }

        return automation.windows.firstNotNullOfOrNull { it.root?.let(::search) }
            ?: automation.rootInActiveWindow?.let(::search)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun report(phase: String, elapsed: Long? = null) {
        val receipt = JSONObject().put("phase", phase).put("background_ms", elapsed)
        android.util.Log.i("ProjectReviewAppAccess", receipt.toString())
    }

    private fun reportState(phase: String, words: Set<String>, activity: MainActivity) {
        val receipt = JSONObject().put("phase", phase)
            .put("locked_title", hasLabel(lockedTitle))
            .put("backup_title", hasLabel(backupTitle))
            .put("wallet_action", hasWalletAction())
            .put("phrase_visibility_checked", words.isNotEmpty())
            .put("phrase_visible", if (words.isNotEmpty()) hasWalletWord(words) else JSONObject.NULL)
            .put("copy_visible", hasLabel(copyLabel))
        onMain {
            receipt.put("activity_resumed", activity.lifecycle.currentState == Lifecycle.State.RESUMED)
                .put("activity_destroyed", activity.isDestroyed)
                .put("activity_focused", activity.hasWindowFocus())
            if (!activity.isDestroyed) {
                val (route, parentRoute) = navigationRoutes(activity)
                receipt.put("main_graph", parentRoute == MainAppGraph::class.qualifiedName)
                    .put("home_route", route?.startsWith("co.electriccoin.zcash.ui.screen.home.HomeArgs") == true)
                    .put("restore_instructions_route", route?.startsWith(
                        "co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs") == true)
                    .put("ready", dependency<WalletRepository>().secretState.value == SecretState.READY)
                val model = androidx.lifecycle.ViewModelProvider(activity)[AuthenticationViewModel::class.java]
                fun value(name: String): Any? = AuthenticationViewModel::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.get(model).let { (it as StateFlow<*>).value }
                receipt.put("auth_state", value("appAccessAuthenticationResultState")?.javaClass?.simpleName)
                    .put("auth_result", value("appAccessResult")?.javaClass?.simpleName)
                    .put("auth_failed", value("authFailed"))
                    .put("welcome_animation", value("showWelcomeAnimation"))
            }
        }
        receipt.put("device_locked", context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked)
        receipt.put("app_active_window", automation.windows.any { window ->
            window.isActive && window.root?.let { root ->
                try { root.packageName == context.packageName } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            } == true
        })
        android.util.Log.i("ProjectReviewAppAccess", receipt.toString())
    }

    // Navigation is an implementation dependency of ui-lib, so this app harness reads its public getters.
    private fun navigationRoutes(activity: MainActivity): Pair<String?, String?> {
        val controller = MainActivity::class.java.getMethod("getNavControllerForTesting").invoke(activity)
        val destination = controller.javaClass.getMethod("getCurrentDestination").invoke(controller)
        val parent = destination?.javaClass?.getMethod("getParent")?.invoke(destination)
        fun route(value: Any?): String? = value?.javaClass?.getMethod("getRoute")?.invoke(value) as? String
        return route(destination) to route(parent)
    }

    private inline fun <reified T : Any> dependency(): T = GlobalContext.get().get()

    private companion object {
        val BACKGROUND_MILLIS = TimeUnit.MINUTES.toMillis(15) + 2_000
        const val UI_TIMEOUT_MILLIS = 25_000L
        const val WALLET_TIMEOUT_MILLIS = 120_000L
        const val POLL_MILLIS = 200L
        const val MAX_SCROLLS = 8
        val walletActionTags = setOf(HomeTags.SEND, HomeTags.RECEIVE, HomeTags.PAY, HomeTags.SWAP)
    }
}
