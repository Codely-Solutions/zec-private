package co.electriccoin.zcash.ui.screen.advancedsettings

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.migration.MigrationGate
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.VersionInfoFixture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * While a view-only account is selected the Advanced settings list offers no Export Private Data and
 * no hardware-wallet disconnect, but keeps Export Viewing Key, which re-exports the key the account
 * was imported from.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedSettingsViewOnlyVMTest {
    private val sdkAccount = Account.new(AccountUuid.new(ByteArray(16) { it.toByte() }))

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aSelectedViewOnlyAccountHidesExportPrivateDataAndKeepsExportViewingKey() =
        runTest {
            val vm = vm(listOf(zodl(isSelected = false), viewOnly(isSelected = true)))
            collect(vm)

            val titles = titles(vm)
            assertFalse(R.string.settings_exportPrivateData in titles)
            assertTrue(R.string.exportViewingKey_settingsItem in titles)
            assertTrue(R.string.taxExport_taxFile in titles)
        }

    @Test
    fun spendableAccountsKeepExportPrivateData() =
        runTest {
            listOf(
                listOf(zodl(isSelected = true), viewOnly(isSelected = false)),
                listOf(zodl(isSelected = false), keystone(isSelected = true)),
            ).forEach { accounts ->
                val vm = vm(accounts)
                collect(vm)

                assertTrue(R.string.settings_exportPrivateData in titles(vm))
            }
        }

    @Test
    fun unresolvedAccountsHideExportAndAStaleTapCannotOpenIt() = runTest {
        val accounts = MutableStateFlow<List<WalletAccount>?>(null)
        val router = mockk<NavigationRouter>(relaxed = true)
        assertFalse(R.string.settings_exportPrivateData in titles(vm(accounts, router)))

        accounts.value = listOf(zodl(isSelected = true))
        val vm = vm(accounts, router)
        val export = vm.state.value.items.single { it.title == stringRes(R.string.settings_exportPrivateData) }
        accounts.value = listOf(viewOnly(isSelected = true))
        export.onClick?.invoke()
        verify(exactly = 0) { router.forward(co.electriccoin.zcash.ui.NavigationTargets.EXPORT_PRIVATE_DATA) }
    }

    private fun titles(vm: AdvancedSettingsVM) =
        vm.state.value.items
            .mapNotNull { (it.title as? StringResource.ByResource)?.resource }

    private fun vm(accounts: List<WalletAccount>) = vm(MutableStateFlow(accounts))

    private fun vm(
        accounts: MutableStateFlow<List<WalletAccount>?>,
        router: NavigationRouter = mockk(relaxed = true),
    ) =
        AdvancedSettingsVM(
            getWalletRestoringState =
                mockk {
                    every { observe() } returns MutableStateFlow(WalletRestoringState.NONE)
                },
            getWalletAccounts =
                mockk {
                    every { observe() } returns accounts
                },
            navigationRouter = router,
            navigateToTaxExport = mockk(relaxed = true),
            navigateToWalletBackup = mockk(relaxed = true),
            getVersionInfo =
                mockk<GetVersionInfoProvider> {
                    every { this@mockk() } returns VersionInfoFixture.new()
                },
            navigateToResetWallet = mockk(relaxed = true),
            migrationGate =
                mockk<MigrationGate> {
                    coEvery { isRestartAvailable() } returns false
                },
            migrationNavigator = mockk(relaxed = true),
        )

    private fun zodl(isSelected: Boolean) =
        ZodlAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            saplingAddress = "s",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
        )

    private fun keystone(isSelected: Boolean) =
        KeystoneAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
        )

    private fun viewOnly(isSelected: Boolean) =
        ViewOnlyAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = isSelected,
            name = stringRes("Savings"),
            customName = "Savings",
            icon = 0,
        )

    private fun TestScope.collect(vm: AdvancedSettingsVM) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
    }
}
