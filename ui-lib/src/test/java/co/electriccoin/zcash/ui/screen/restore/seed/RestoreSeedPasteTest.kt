package co.electriccoin.zcash.ui.screen.restore.seed

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.ClearClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ClipboardTextIdentity
import co.electriccoin.zcash.ui.common.usecase.ClipboardTextSample
import co.electriccoin.zcash.ui.common.usecase.ReadClipboardTextUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeight
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSeedPasteTest {
    private val viewModels = mutableListOf<RestoreSeedVM>()
    private val readClipboard = mockk<ReadClipboardTextUseCase>()
    private val clearClipboard = mockk<ClearClipboardUseCase>(relaxed = true)
    private val words = List(24) { "fixture" }
    private val phrase = mockk<SeedPhrase>()
    private val validate = mockk<ValidateSeedUseCase>()
    private val navigation = mockk<NavigationRouter>(relaxed = true)

    init {
        every { phrase.split } returns words
        every { validate(any()) } answers { if (firstArg<List<String>>() == words) phrase else null }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun wholePhraseFieldPasteWorksInRelease() = runVmTest {
        val vm = vm()
        val initial = vm.state.first { it != null }!!
        initial.seed.values.first().onValueChange(SeedWordInnerTextFieldState(words.joinToString(" ")))
        val changed = vm.state.first { it?.seed?.values?.first()?.innerState?.value?.isNotBlank() == true }!!
        assertEquals(words, changed.seed.values.map { it.innerState.value })
        verify(exactly = 0) { readClipboard() }
    }

    @Test
    fun explicitPasteNormalizesWhitespaceAndCaseThenClearsUnchangedClip() = runVmTest {
        val pasted = " \n" + words.joinToString("\t\n\u00a0") { it.uppercase() } + " \r\n"
        every { readClipboard() } returns sample(pasted)
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val changed = vm.state.first { it?.nextButton != null }!!
        assertEquals(words, changed.seed.values.map { it.innerState.value })
        assertFalse(changed.isPasteError)
        verify(exactly = 1) { clearClipboard(any()) }
    }

    @Test
    fun aClipWithoutOwnershipIsPreserved() = runVmTest {
        every { readClipboard() } returns ClipboardTextSample(words.joinToString(" "), ownership = null)
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        vm.state.first { it?.nextButton != null }
        verify(exactly = 0) { clearClipboard(any()) }
    }

    @Test
    fun enteringBirthdayCreatesAnOpaqueSessionAndCompletionClearsThePhrase() = runVmTest {
        every { readClipboard() } returns sample(words.joinToString(" "))
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val entered = vm.state.first { it?.nextButton != null }!!

        entered.nextButton!!.onClick()
        advanceUntilIdle()
        val route = slot<RestoreHeight>()
        verify(exactly = 1) { navigation.forward(capture(route)) }
        val sessionId = route.captured.sessionId
        assertFalse(sessionId.contains(words.first()))
        assertEquals(phrase, vm.restoreSession.resolve(sessionId))

        vm.completeRestore(sessionId)
        val cleared =
            vm.state.first { state ->
                state != null && state.nextButton == null && state.seed.values.all { it.innerState.value.isEmpty() }
            }!!
        assertNull(vm.restoreSession.resolve(sessionId))
        assertNull(cleared.nextButton)
    }

    @Test
    fun returningToSeedEntryRevokesBirthdaySessionWhileKeepingEditableWords() = runVmTest {
        every { readClipboard() } returns sample(words.joinToString(" "))
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val entered = vm.state.first { it?.nextButton != null }!!

        entered.nextButton!!.onClick()
        advanceUntilIdle()
        val route = slot<RestoreHeight>()
        verify(exactly = 1) { navigation.forward(capture(route)) }
        vm.restoreSession.clear(route.captured.sessionId)

        assertNull(vm.restoreSession.resolve(route.captured.sessionId))
        assertEquals(words, vm.state.first { it?.nextButton != null }!!.seed.values.map { it.innerState.value })
    }

    @Test
    fun invalidPasteKeepsManualWordsAndClipboard() = runVmTest {
        every { readClipboard() } returns sample(List(24) { "invalid" }.joinToString(" "))
        val vm = vm()
        vm.state.first { it != null }!!.seed.values.first().onValueChange(SeedWordInnerTextFieldState("manual"))
        vm.state.first { it?.seed?.values?.first()?.innerState?.value == "manual" }!!.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertEquals("manual", rejected.seed.values.first().innerState.value)
        verify(exactly = 0) { clearClipboard(any()) }
    }

    @Test
    fun supportedBip39ShortPhraseCannotFillTwentyFourFields() = runVmTest {
        val shortPhrase = Mnemonics.MnemonicCode(ByteArray(16)).words.map { it.concatToString() }
        every { readClipboard() } returns sample(shortPhrase.joinToString(" "))
        val vm = vm(ValidateSeedUseCase())
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertTrue(rejected.seed.values.all { it.innerState.value.isEmpty() })
        verify(exactly = 0) { clearClipboard(any()) }
    }

    @Test
    fun deniedClipboardReadLeavesFieldsUntouched() = runVmTest {
        every { readClipboard() } returns null
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertTrue(rejected.seed.values.all { it.innerState.value.isEmpty() })
        verify(exactly = 0) { clearClipboard(any()) }
    }

    @Test
    fun aRetainedContinueActionCannotAcceptThePriorPhraseAfterInvalidPaste() = runVmTest {
        every { readClipboard() } returns sample(words.joinToString(" "))
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val accepted = vm.state.first { it?.nextButton != null }!!
        every { readClipboard() } returns sample("invalid clipboard content")
        accepted.pasteButton!!.onClick()
        vm.state.first { it?.isPasteError == true }

        accepted.nextButton!!.onClick()
        advanceUntilIdle()

        verify(exactly = 0) { navigation.forward(any()) }
    }

    @Test
    fun actualBip39ChecksumIsRequired() = runVmTest {
        // Generate a public test vector in memory; assertions never print phrase contents.
        val generated = Mnemonics.MnemonicCode(ByteArray(32)).words.map { it.concatToString() }
        every { readClipboard() } returns sample(generated.joinToString(" "))
        val vm = vm(ValidateSeedUseCase())
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val accepted = vm.state.first { it?.nextButton != null }!!
        assertTrue(accepted.seed.values.map { it.innerState.value } == generated)
        every { readClipboard() } returns sample(List(24) { generated.first() }.joinToString(" "))
        accepted.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertTrue(rejected.seed.values.map { it.innerState.value } == generated)
        assertNull(rejected.nextButton)
    }

    private fun sample(text: String) = ClipboardTextSample(text, ClipboardTextIdentity(1, "fixture", text))

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            viewModels.clear()
        }
    }

    private fun vm(validation: ValidateSeedUseCase = validate) = RestoreSeedVM(
        navigationRouter = navigation,
        validateSeed = validation,
        readClipboardText = readClipboard,
        clearClipboard = clearClipboard
    ).also { viewModels += it }
}
