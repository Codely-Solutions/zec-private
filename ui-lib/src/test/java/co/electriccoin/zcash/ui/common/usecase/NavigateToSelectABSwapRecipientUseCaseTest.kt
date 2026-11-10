package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.SwapBlockchain
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.swap.ab.SelectABSwapRecipientArgs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Contact selection runs inside the app-access gate and preserves picker cancellation and request matching. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigateToSelectABSwapRecipientUseCaseTest {
    private val forwarded = mutableListOf<Any>()
    private val router =
        mockk<NavigationRouter>(relaxed = true) {
            every { forward(*anyVararg()) } answers {
                (args[0] as Array<*>).filterNotNull().forEach { forwarded.add(it) }
            }
        }
    private val useCase = NavigateToSelectABSwapRecipientUseCase(router)

    @Test
    fun selectionResolvesToContactAndPopsBack() =
        runTest(UnconfinedTestDispatcher()) {
            val result = async { useCase() }
            val args = forwarded.single() as SelectABSwapRecipientArgs

            val contact = contact("Alice")
            useCase.onSelected(contact, args)

            assertEquals(contact, result.await())
            verify(exactly = 1) { router.back() }
        }

    @Test
    fun networkContextRejectsMismatchedContactAndAcceptsMatchingContact() =
        runTest(UnconfinedTestDispatcher()) {
            val result = async { useCase(network = "eth") }
            val args = forwarded.single() as SelectABSwapRecipientArgs
            assertEquals("eth", args.network)

            useCase.onSelected(contact("Other network"), args)
            assertTrue(result.isActive)
            verify(exactly = 0) { router.back() }

            val matching =
                contact("Alice").copy(
                    blockchain = SwapBlockchain("ETH", stringRes("Ethereum"), imageRes("E"))
                )
            useCase.onSelected(matching, args)
            assertEquals(matching, result.await())
            verify(exactly = 1) { router.back() }
        }

    @Test
    fun cancellationResolvesToNullAndPopsBack() =
        runTest(UnconfinedTestDispatcher()) {
            val result = async { useCase() }
            val args = forwarded.single() as SelectABSwapRecipientArgs

            useCase.onSelectionCancelled(args)

            assertNull(result.await())
            verify(exactly = 1) { router.back() }
        }

    @Test
    fun ignoresResultFromADifferentRequest() =
        runTest(UnconfinedTestDispatcher()) {
            val result = async { useCase() }
            val args = forwarded.single() as SelectABSwapRecipientArgs

            useCase.onSelected(contact("Stale"), args.copy(requestId = "stale"))
            assertTrue(result.isActive)

            val fresh = contact("Fresh")
            useCase.onSelected(fresh, args)
            assertEquals(fresh, result.await())
        }

    private fun contact(name: String) =
        EnhancedABContact(
            contact =
                AddressBookContact(
                    name = name,
                    address = "address-$name",
                    lastUpdated = Instant.fromEpochMilliseconds(0),
                    chain = null
                ),
            blockchain = null
        )
}
