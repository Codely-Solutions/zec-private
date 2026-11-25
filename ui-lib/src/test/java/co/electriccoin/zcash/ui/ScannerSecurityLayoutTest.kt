package co.electriccoin.zcash.ui

import android.view.WindowManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import co.electriccoin.zcash.ui.common.compose.LocalScreenSecurity
import co.electriccoin.zcash.ui.common.compose.ScreenSecurity
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressArgs
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressScreen
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressVM
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressScreen
import co.electriccoin.zcash.ui.screen.scan.ScanZashiAddressVM
import co.electriccoin.zcash.ui.screen.scankeystone.model.ScanKeystoneState
import co.electriccoin.zcash.ui.screen.scankeystone.view.ScanKeystoneView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionState
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionView
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.ZashiAccountInfoListItemState
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp")
class ScannerSecurityLayoutTest {
    private val contexts = mutableListOf<KoinApplication>()

    @get:Rule(order = 0)
    val closeContexts = object : ExternalResource() {
        override fun after() = contexts.forEach(KoinApplication::close)
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()
    private val router = mockk<NavigationRouter>(relaxed = true)

    @Test
    fun sharedKeystoneViewsProtectTheCameraAndTheFullscreenSigningQr() {
        val security = ScreenSecurity()
        val showCamera = mutableStateOf(true)
        val showSigning = mutableStateOf(false)
        val button = ButtonState(stringRes("Fixture action"))
        compose.setContent {
            CompositionLocalProvider(LocalScreenSecurity provides security) {
                ZcashTheme {
                    if (showCamera.value) {
                        ScanKeystoneView(
                            snackbarHostState = remember { SnackbarHostState() },
                            onBack = {}, onScan = {}, onOpenSettings = {}, onScanStateChange = {},
                            validationResult = ScanValidationState.NONE,
                            state = ScanKeystoneState(stringRes("Fixture scanner"), null),
                        )
                    }
                    if (showSigning.value) {
                        SignKeystoneTransactionView(
                            SignKeystoneTransactionState(
                                onBack = {}, barTitle = stringRes("Fixture signing"),
                                title = stringRes("Scan with Keystone"), subtitle = stringRes("Fixture details"),
                                accountInfo = ZashiAccountInfoListItemState(
                                    co.electriccoin.zcash.ui.design.R.drawable.ic_item_keystone,
                                    stringRes("Keystone"), stringRes("Fixture account")
                                ),
                                badgeText = stringRes("Hardware"), qrData = "fixture-signing-qr",
                                generateNextQrCode = {}, secondaryButton = null,
                                positiveButton = button, negativeButton = button,
                            )
                        )
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            showCamera.value = false
            showSigning.value = true
        }
        val label = context.getString(R.string.private_keystone_open_qr)
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            val window = checkNotNull(ShadowDialog.getLatestDialog()?.window)
            val flag = WindowManager.LayoutParams.FLAG_SECURE
            assertEquals(flag, window.attributes.flags and flag)
            showSigning.value = false
        }
        compose.runOnIdle { assertEquals(0, security.referenceCount.value) }
    }

    @Test
    fun bothGeneralScannersAcquireScreenProtectionBeforeRenderingCameraContent() {
        val security = ScreenSecurity()
        val destination = mutableStateOf<ScanFlow?>(null)
        val dependencies = module {
            single<NavigationRouter> { router }
            viewModel {
                mockk<WalletViewModel>(relaxed = true) { every { synchronizer } returns MutableStateFlow(null) }
            }
            viewModel {
                mockk<ScanZashiAddressVM>(relaxed = true) {
                    every { state } returns MutableStateFlow(ScanValidationState.NONE)
                }
            }
            viewModel {
                mockk<ScanGenericAddressVM>(relaxed = true) { every { isOwned } returns MutableStateFlow(false) }
            }
        }
        val testContext = koinApplication { modules(dependencies) }.also(contexts::add)
        compose.setContent {
            KoinIsolatedContext(context = testContext) {
                ZcashTheme {
                    CompositionLocalProvider(LocalScreenSecurity provides security) {
                        when (destination.value) {
                            ScanFlow.HOMEPAGE -> ScanZashiAddressScreen(ScanArgs(ScanFlow.HOMEPAGE), "wallet")
                            ScanFlow.SEND -> ScanGenericAddressScreen(ScanGenericAddressArgs("generic"), "generic")
                            else -> Text("Outside scanner")
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, security.referenceCount.value)
            destination.value = ScanFlow.HOMEPAGE
        }
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            destination.value = ScanFlow.SEND
        }
        compose.runOnIdle {
            assertEquals(1, security.referenceCount.value)
            destination.value = null
        }
        compose.runOnIdle { assertEquals(0, security.referenceCount.value) }
    }
}
