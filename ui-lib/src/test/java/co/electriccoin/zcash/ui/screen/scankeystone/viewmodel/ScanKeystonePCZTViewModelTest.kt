package co.electriccoin.zcash.ui.screen.scankeystone.viewmodel

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.repository.KeystoneFirmwareBelowMinimumException
import co.electriccoin.zcash.ui.common.repository.ParsePCZTException
import co.electriccoin.zcash.ui.common.usecase.InvalidKeystonePCZTQRException
import co.electriccoin.zcash.ui.common.usecase.ParseKeystonePCZTUseCase
import co.electriccoin.zcash.ui.common.usecase.ParseKeystoneQrResult
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorVM
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ScanKeystonePCZTViewModelTest {
    private val parse = mockk<ParseKeystonePCZTUseCase>()
    private val router = mockk<NavigationRouter>()
    private val navigateToError = NavigateToErrorUseCase(router)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun scannedFragmentsUpdateProgress() = runTest {
        coEvery { parse("first-fragment") } returns ParseKeystoneQrResult(37, isFinished = false)
        coEvery { parse("last-fragment") } returns ParseKeystoneQrResult(100, isFinished = true)
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError)

        assertNull(vm.state.value.progress)
        vm.onScanned("first-fragment").join()
        assertEquals(37, vm.state.value.progress)
        vm.onScanned("last-fragment").join()
        assertEquals(100, vm.state.value.progress)
        assertEquals(ScanValidationState.NONE, vm.validationState.value)
    }

    @Test
    fun unreadableQrIsInvalid() = runTest {
        assertInvalidScan(InvalidKeystonePCZTQRException())
    }

    @Test
    fun unreadablePcztIsInvalid() = runTest {
        assertInvalidScan(ParsePCZTException())
    }

    @Test
    fun closingFirmwareSheetReturnsToSigning() = runTest {
        val routes = mutableListOf<Any>(SignKeystoneTransactionArgs, ScanKeystonePCZTRequest)
        every { router.forward(ErrorBottomSheet) } answers { routes.add(ErrorBottomSheet) }
        every { router.replace(ErrorBottomSheet) } answers {
            routes.removeAt(routes.lastIndex)
            routes.add(ErrorBottomSheet)
        }
        every { router.back() } answers { routes.removeAt(routes.lastIndex) }
        val obsolete = KeystoneFirmwareBelowMinimumException(detected = null)
        coEvery { parse("legacy-signer-qr") } throws obsolete
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError)

        vm.onScanned("legacy-signer-qr").join()

        assertEquals(listOf(SignKeystoneTransactionArgs, ErrorBottomSheet), routes)
        assertEquals(ScanValidationState.NONE, vm.validationState.value)
        val args = assertIs<ErrorArgs.KeystoneFirmwareUpdateRequired>(navigateToError.requireCurrentArgs())
        assertSame(obsolete, args.exception)
        val sheet = ErrorVM(args, navigateToError, router, mockk(), mockk()).state.value
        assertEquals(stringRes(R.string.error_keystone_firmware_title), sheet.title)
        sheet.positive.onClick()

        assertEquals(listOf(SignKeystoneTransactionArgs), routes)
    }

    private suspend fun assertInvalidScan(error: Exception) {
        coEvery { parse("invalid-qr") } throws error
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError)

        vm.onScanned("invalid-qr").join()

        assertEquals(ScanValidationState.INVALID, vm.validationState.value)
        assertNull(vm.state.value.progress)
        verify { router wasNot Called }
    }
}
