package co.electriccoin.zcash.ui.screen.restore.seed

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.ClearClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.ReadClipboardTextUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
    private val readClipboard = mockk<ReadClipboardTextUseCase>()
    private val clearClipboard = mockk<ClearClipboardUseCase>(relaxed = true)
    private val words = List(24) { "fixture" }
    private val phrase = mockk<SeedPhrase>()
    private val validate = mockk<ValidateSeedUseCase>()

    init {
        every { phrase.split } returns words
        every { validate(any()) } answers { if (firstArg<List<String>>() == words) phrase else null }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun wholePhraseFieldPasteWorksInRelease() = runTest {
        val vm = vm()
        val initial = vm.state.first { it != null }!!
        initial.seed.values.first().onValueChange(SeedWordInnerTextFieldState(words.joinToString(" ")))
        val changed = vm.state.first { it?.seed?.values?.first()?.innerState?.value?.isNotBlank() == true }!!
        assertEquals(words, changed.seed.values.map { it.innerState.value })
        verify(exactly = 0) { readClipboard() }
    }

    @Test
    fun explicitPasteNormalizesWhitespaceAndCaseThenClearsUnchangedClip() = runTest {
        val pasted = " \n" + words.joinToString("\t\n\u00a0") { it.uppercase() } + " \r\n"
        every { readClipboard() } returns pasted
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val changed = vm.state.first { it?.nextButton != null }!!
        assertEquals(words, changed.seed.values.map { it.innerState.value })
        assertFalse(changed.isPasteError)
        verify(exactly = 1) { clearClipboard() }
    }

    @Test
    fun newerClipboardIsPreserved() = runTest {
        every { readClipboard() } returnsMany listOf(words.joinToString(" "), "newer text")
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        vm.state.first { it?.nextButton != null }
        verify(exactly = 0) { clearClipboard() }
    }

    @Test
    fun invalidPasteKeepsManualWordsAndClipboard() = runTest {
        every { readClipboard() } returns List(24) { "invalid" }.joinToString(" ")
        val vm = vm()
        vm.state.first { it != null }!!.seed.values.first().onValueChange(SeedWordInnerTextFieldState("manual"))
        vm.state.first { it?.seed?.values?.first()?.innerState?.value == "manual" }!!.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertEquals("manual", rejected.seed.values.first().innerState.value)
        verify(exactly = 0) { clearClipboard() }
    }

    @Test
    fun supportedBip39ShortPhraseCannotFillTwentyFourFields() = runTest {
        val shortPhrase = Mnemonics.MnemonicCode(ByteArray(16)).words.map { it.concatToString() }
        every { readClipboard() } returns shortPhrase.joinToString(" ")
        val vm = vm(ValidateSeedUseCase())
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertTrue(rejected.seed.values.all { it.innerState.value.isEmpty() })
        verify(exactly = 0) { clearClipboard() }
    }

    @Test
    fun deniedClipboardReadLeavesFieldsUntouched() = runTest {
        every { readClipboard() } throws SecurityException()
        val vm = vm()
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertTrue(rejected.seed.values.all { it.innerState.value.isEmpty() })
        verify(exactly = 0) { clearClipboard() }
    }

    @Test
    fun actualBip39ChecksumIsRequired() = runTest {
        // Generate a public test vector in memory; assertions never print phrase contents.
        val generated = Mnemonics.MnemonicCode(ByteArray(32)).words.map { it.concatToString() }
        every { readClipboard() } returns generated.joinToString(" ")
        val vm = vm(ValidateSeedUseCase())
        vm.state.first { it != null }!!.pasteButton!!.onClick()
        val accepted = vm.state.first { it?.nextButton != null }!!
        assertTrue(accepted.seed.values.map { it.innerState.value } == generated)
        every { readClipboard() } returns List(24) { generated.first() }.joinToString(" ")
        accepted.pasteButton!!.onClick()
        val rejected = vm.state.first { it?.isPasteError == true }!!
        assertTrue(rejected.seed.values.map { it.innerState.value } == generated)
        assertNull(rejected.nextButton)
    }

    private fun vm(validation: ValidateSeedUseCase = validate) = RestoreSeedVM(
        navigationRouter = mockk<NavigationRouter>(relaxed = true),
        validateSeed = validation,
        readClipboardText = readClipboard,
        clearClipboard = clearClipboard
    )
}
