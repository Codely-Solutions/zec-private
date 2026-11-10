package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.addressbook.SelectABRecipientArgs
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class NavigateToSelectRecipientUseCaseTest {
    @Test
    fun unlockedContactSelectionNavigatesDirectly() = runTest {
        val router = mockk<NavigationRouter>(relaxed = true)
        NavigateToSelectRecipientUseCase(router)()
        verify(exactly = 1) { router.forward(SelectABRecipientArgs) }
    }
}
