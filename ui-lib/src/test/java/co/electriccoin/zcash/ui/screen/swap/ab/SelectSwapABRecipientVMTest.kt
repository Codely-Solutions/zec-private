package co.electriccoin.zcash.ui.screen.swap.ab

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.usecase.GetABSwapContactsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToScanGenericAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.ScanResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectSwapABRecipientVMTest {
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private val scan = mockk<NavigateToScanGenericAddressUseCase>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun scannedContactReplacesScannerAndPreservesNetwork() = runTest {
        val address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT"
        coEvery { scan() } returns ScanResult(address, null)
        val vm = viewModel()

        vm.state.value.scanButton.onClick()
        runCurrent()

        verify(exactly = 1) { navigation.replace(AddSwapABContactArgs(address = address, network = "btc")) }
        verify(exactly = 0) { navigation.forward(ofType<AddSwapABContactArgs>()) }
    }

    @Test
    fun manualEntryPushesContactFormAndPreservesPicker() {
        val vm = viewModel()

        vm.state.value.manualButton.onClick()

        verify(exactly = 1) { navigation.forward(AddSwapABContactArgs(address = null, network = "btc")) }
        verify(exactly = 0) { navigation.replace(ofType<AddSwapABContactArgs>()) }
    }

    @Test
    fun cancelledScanLeavesNavigationToScannerOwner() = runTest {
        coEvery { scan() } returns null
        val vm = viewModel()

        vm.state.value.scanButton.onClick()
        runCurrent()

        verify(exactly = 0) { navigation.replace(any()) }
        verify(exactly = 0) { navigation.forward(any()) }
    }

    private fun viewModel(): SelectSwapABRecipientVM {
        val contacts = mockk<GetABSwapContactsUseCase> {
            every { observe() } returns MutableStateFlow<List<EnhancedABContact>?>(emptyList())
        }
        return SelectSwapABRecipientVM(
            getAddressBookSwapContacts = contacts,
            args = SelectABSwapRecipientArgs(network = "btc"),
            navigateToSelectSwapRecipient = mockk(relaxed = true),
            navigationRouter = navigation,
            navigateToScanAddress = scan,
        )
    }
}
