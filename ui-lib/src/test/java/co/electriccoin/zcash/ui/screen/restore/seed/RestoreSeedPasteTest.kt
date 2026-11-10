package co.electriccoin.zcash.ui.screen.restore.seed

import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.SeedWordInnerTextFieldState
import io.mockk.every
import io.mockk.mockk
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

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreSeedPasteTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun wholePhraseFieldPasteIsDebugOnly() = runTest {
        val words = List(24) { "fixture" }
        val phrase = mockk<SeedPhrase>()
        every { phrase.split } returns words
        val validate = mockk<ValidateSeedUseCase>()
        every { validate(any()) } answers { if (firstArg<List<String>>() == words) phrase else null }
        val vm = RestoreSeedVM(mockk<NavigationRouter>(relaxed = true), validate)
        val initial = vm.state.first { it != null }!!
        val pasted = words.joinToString(" ")
        initial.seed.values.first().onValueChange(SeedWordInnerTextFieldState(pasted))
        val changed = vm.state.first { it?.seed?.values?.first()?.innerState?.value?.isNotBlank() == true }!!
        assertEquals(
            if (BuildConfig.DEBUG) words else listOf(pasted) + List(23) { "" },
            changed.seed.values.map { it.innerState.value }
        )
    }
}
