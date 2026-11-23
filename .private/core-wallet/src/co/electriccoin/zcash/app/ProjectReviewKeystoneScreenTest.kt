package co.electriccoin.zcash.app

import android.annotation.TargetApi
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.MainActivity
import co.electriccoin.zcash.ui.OnboardingGraph
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Checks the actual enrollment destination and Android window on a blank local API 35 guest. */
class ProjectReviewKeystoneScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun seedlessScannerSecuresTheWindowUntilItLeavesTheBackStack() = runBlocking {
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(Build.VERSION.SDK_INT >= 35)
        check(ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet)
        check(InstrumentationRegistry.getArguments().getString("isolatedTestnet") == "valueless-coins-only")
        val koin = GlobalContext.get()
        check(!koin.get<GetVersionInfoProvider>()().isRunningUnderTestService)
        check(koin.get<PersistableWalletProvider>().getPersistableWallet() == null)

        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(launch) as MainActivity
        try {
            val onboarding = OnboardingGraph::class.qualifiedName
            val scanner = ScanKeystoneSignInRequest::class.qualifiedName
            await("unsecured onboarding window") {
                onMain {
                    activity.walletViewModel.secretState.value == SecretState.NONE &&
                        activity.hasWindowFocus() && activity.graphRoute() == onboarding && !activity.isSecure
                }
            }
            await("onboarding content available in the Android window screenshot") {
                windowHasVisibleContent(activity)
            }

            onMain { activity.navControllerForTesting.navigate(ScanKeystoneSignInRequest) }
            await("secured production scanner window") {
                onMain { activity.hasWindowFocus() && activity.route() == scanner && activity.isSecure }
            }
            await("scanner content excluded from the Android window screenshot") {
                !windowHasVisibleContent(activity)
            }

            onMain { check(activity.navControllerForTesting.popBackStack()) }
            await("scanner window protection released") {
                onMain { activity.hasWindowFocus() && activity.graphRoute() == onboarding && !activity.isSecure }
            }
            await("onboarding window capturable again") {
                windowHasVisibleContent(activity)
            }
        } finally {
            onMain { activity.finish() }
        }
    }

    private val MainActivity.isSecure: Boolean
        get() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun MainActivity.route(): String? = navControllerForTesting.currentDestination?.route

    private fun MainActivity.graphRoute(): String? = runCatching {
        navControllerForTesting.currentDestination?.parent?.route
    }.getOrNull()

    private suspend fun await(description: String, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(15_000L) {
            while (!condition()) delay(50L)
            true
        }
        check(reached == true) { "Timed out waiting for $description" }
    }

    @TargetApi(34)
    private fun windowHasVisibleContent(activity: MainActivity): Boolean {
        val screenshot: Bitmap = instrumentation.uiAutomation.takeScreenshot(activity.window) ?: return false
        try {
            val xStep = (screenshot.width / 32).coerceAtLeast(1)
            val yStep = (screenshot.height / 32).coerceAtLeast(1)
            return (xStep until screenshot.width step xStep).any { x ->
                (yStep until screenshot.height step yStep).any { y ->
                    val pixel = screenshot.getPixel(x, y)
                    Color.alpha(pixel) != 0 && pixel and 0x00ffffff != 0
                }
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
