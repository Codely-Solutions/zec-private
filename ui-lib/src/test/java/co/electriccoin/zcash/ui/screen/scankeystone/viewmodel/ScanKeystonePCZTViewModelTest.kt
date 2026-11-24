package co.electriccoin.zcash.ui.screen.scankeystone.viewmodel

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import co.electriccoin.zcash.ui.common.repository.KeystoneFirmwareBelowMinimumException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.ParsePCZTException
import co.electriccoin.zcash.ui.common.usecase.InvalidKeystonePCZTQRException
import co.electriccoin.zcash.ui.common.usecase.ParseKeystonePCZTUseCase
import co.electriccoin.zcash.ui.common.usecase.ParseKeystoneQrResult
import co.electriccoin.zcash.ui.common.usecase.PrefillSendUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitKSProposalUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorVM
import co.electriccoin.zcash.ui.screen.error.noReportAccess
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.scan.ScanValidationState
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystonePCZTRequest
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import com.keystone.module.DecodeResult
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.URDecoder
import com.sparrowwallet.hummingbird.UREncoder
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    private val route = ScanKeystonePCZTRequest("prepared")
    private val ready = mockk<KeystoneProposalRepository> {
        every { signingPreparationId() } returns route.preparationId
    }

    @Test
    fun scannedFragmentsUpdateProgress() = runTest {
        coEvery { parse("first-fragment", route.preparationId) } returns ParseKeystoneQrResult(37, isFinished = false)
        coEvery { parse("last-fragment", route.preparationId) } returns ParseKeystoneQrResult(100, isFinished = true)
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError, ready)

        assertNull(vm.state.value.progress)
        vm.onScanned("first-fragment", route).join()
        assertEquals(37, vm.state.value.progress)
        vm.onScanned("last-fragment", route).join()
        assertEquals(100, vm.state.value.progress)
        assertEquals(ScanValidationState.NONE, vm.validationState.value)
    }

    @Test
    fun completedScanSubmitsTheApprovedProposalOnceAndOpensProgress() = runTest {
        val signer = mockk<KeystoneSDKProvider>()
        val accountDataSource = mockk<AccountDataSource> { coEvery { getSelectedAccount() } returns mockk() }
        val proposalDataSource = mockk<ProposalDataSource>()
        val sdkProposal = mockk<Proposal>()
        val repository = KeystoneProposalRepositoryImpl(accountDataSource, proposalDataSource, signer, mockk())
        val prefillSend = mockk<PrefillSendUseCase>(relaxed = true)
        val submit = SubmitKSProposalUseCase(repository, router, mockk(relaxed = true), mockk(), prefillSend)
        submit.scope = this
        val parser = ParseKeystonePCZTUseCase(submit, repository, signer)
        val vm = ScanKeystonePCZTViewModel(parser, navigateToError, repository)

        val unsigned = Pczt(byteArrayOf(1))
        val proven = Pczt(byteArrayOf(2))
        val signed = "keystone:fw_version".encodeToByteArray() + byteArrayOf(3, 13, 0, 1)
        val signedUr = UR.fromBytes("zcash-pczt", signed)
        val encoder = UREncoder(signedUr, 10, 10, 0L)
        val fragments = List(encoder.seqLen) { encoder.nextPart() }
        val decoder = URDecoder()
        val submitted = CompletableDeferred<Pair<ByteArray, ByteArray>>()
        val destination = WalletAddress.Unified.new("synthetic-recipient")
        val amount = Zatoshi(1234)
        val memo = Memo("")
        val approved = RegularTransactionProposal(destination, amount, memo, sdkProposal)
        val send = ZecSend(destination = destination, amount = amount, memo = memo, proposal = null)
        coEvery { proposalDataSource.createProposal(any(), send) } returns approved
        coEvery { proposalDataSource.createPcztFromProposal(any(), sdkProposal) } returns unsigned
        coEvery { proposalDataSource.addProofsToPczt(any()) } returns proven
        coEvery { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            submitted.complete(firstArg<Pczt>().toByteArray() to secondArg<Pczt>().toByteArray())
            SubmitResult.Success(listOf("synthetic-transaction"))
        }
        // The Android Keystone adapter loads JNI. Keep its JVM UR decoding at the test boundary.
        every { signer.decodeQR(any()) } answers {
            check(decoder.receivePart(firstArg()))
            val decoded = decoder.result?.ur
            val progress = if (decoded == null) (decoder.estimatedPercentComplete * 100).toInt() else 100
            DecodeResult(progress, decoded)
        }
        every { signer.parsePczt(signedUr) } answers { firstArg<UR>().toBytes() }
        every { router.replace(TransactionProgressArgs) } returns Unit

        repository.createProposal(send)
        repository.createPCZTFromProposal()
        repository.approveForSigning(approved)
        val route = ScanKeystonePCZTRequest(checkNotNull(repository.signingPreparationId()))
        assertTrue(vm.canScan(route))

        vm.onScanned(fragments.first(), route).join()
        assertTrue(assertNotNull(vm.state.value.progress) in 1..99)
        verify(exactly = 0) { router.replace(TransactionProgressArgs) }
        fragments.drop(1).dropLast(1).forEach { vm.onScanned(it, route).join() }

        List(3) { vm.onScanned(fragments.last(), route) }.joinAll()

        assertEquals(100, vm.state.value.progress)
        assertEquals(ScanValidationState.NONE, vm.validationState.value)
        verify(exactly = 1) { signer.decodeQR(fragments.last()) }
        verify(exactly = 1) { signer.parsePczt(signedUr) }
        verify(exactly = 1) { router.replace(TransactionProgressArgs) }
        val (proofBytes, signatureBytes) = submitted.await()
        assertContentEquals(proven.toByteArray(), proofBytes)
        assertContentEquals(signed, signatureBytes)
        coVerify(exactly = 1) { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any()) }
    }

    @Test
    fun unreadableQrIsInvalid() = runTest {
        assertInvalidScan(InvalidKeystonePCZTQRException())
    }

    @Test
    fun coldScannerCannotDecodeWithoutPreparation() = runTest {
        val repository = KeystoneProposalRepositoryImpl(mockk(), mockk(), mockk(), mockk())
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError, repository)
        assertFalse(vm.canScan(route))
        vm.onScanned("synthetic-qr", route).join()
        coVerify(exactly = 0) { parse(any(), any()) }
        assertEquals(ScanValidationState.NONE, vm.validationState.value)
    }

    @Test
    fun unreadablePcztIsInvalid() = runTest {
        assertInvalidScan(ParsePCZTException())
    }

    @Test
    fun closingFirmwareSheetReturnsToSigning() = runTest {
        val routes = mutableListOf<Any>(SignKeystoneTransactionArgs, route)
        every { router.forward(ofType<ErrorBottomSheet>()) } answers { routes.add(firstArg<Array<Any>>().single()) }
        every { router.replace(ofType<ErrorBottomSheet>()) } answers {
            routes.removeAt(routes.lastIndex)
            routes.add(firstArg<Array<Any>>().single())
        }
        every { router.back() } answers { routes.removeAt(routes.lastIndex) }
        val obsolete = KeystoneFirmwareBelowMinimumException(detected = null)
        coEvery { parse("legacy-signer-qr", route.preparationId) } throws obsolete
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError, ready)

        vm.onScanned("legacy-signer-qr", route).join()

        assertEquals(SignKeystoneTransactionArgs, routes.first())
        val errorRoute = assertIs<ErrorBottomSheet>(routes.last())
        assertEquals(ScanValidationState.NONE, vm.validationState.value)
        val args = assertIs<ErrorArgs.KeystoneFirmwareUpdateRequired>(navigateToError.argsFor(errorRoute))
        assertSame(obsolete, args.exception)
        val sheet = ErrorVM(args, navigateToError, router, mockk(), mockk(), noReportAccess()).state.value
        assertEquals(stringRes(R.string.error_keystone_firmware_title), sheet.title)
        sheet.positive.onClick()

        assertEquals(listOf<Any>(SignKeystoneTransactionArgs), routes)
    }

    private suspend fun assertInvalidScan(error: Exception) {
        coEvery { parse("invalid-qr", route.preparationId) } throws error
        val vm = ScanKeystonePCZTViewModel(parse, navigateToError, ready)

        vm.onScanned("invalid-qr", route).join()

        assertEquals(ScanValidationState.INVALID, vm.validationState.value)
        assertNull(vm.state.value.progress)
        verify { router wasNot Called }
    }
}
