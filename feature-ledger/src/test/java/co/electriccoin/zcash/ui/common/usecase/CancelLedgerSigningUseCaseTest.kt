package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.LedgerProposalRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test

/**
 * [CancelLedgerSigningUseCase] leaves the sign sheet for the confirm surface beneath it, which keeps
 * its own proposal - except a shield proposal, which has no confirm surface holding it and is
 * dropped along with the session. A sheet that is gone stops its session without navigating,
 * unless the device has signed and submission is under way.
 */
class CancelLedgerSigningUseCaseTest {
    @Test
    fun cancelNavigatesBackAfterRepositoryAdmission() {
        val repository = repository(admitted = true)
        val router = mockk<NavigationRouter>(relaxed = true)

        CancelLedgerSigningUseCase(repository, router)()

        verify(exactly = 1) { repository.cancelUnsignedTransaction() }
        verify(exactly = 1) { router.back() }
    }

    @Test
    fun aRefusedCancellationDoesNotNavigateBack() {
        val repository = repository(admitted = false)
        val router = mockk<NavigationRouter>(relaxed = true)

        CancelLedgerSigningUseCase(repository, router)()

        verify(exactly = 1) { repository.cancelUnsignedTransaction() }
        verify(exactly = 0) { router.back() }
    }

    @Test
    fun stoppingTheSheetRequestsCancellationOfOnlyAnUnsignedSession() {
        val repository = repository(admitted = true)
        val router = mockk<NavigationRouter>(relaxed = true)

        CancelLedgerSigningUseCase(repository, router).stopSession()

        verify(exactly = 1) { repository.cancelSigning(onlyUnsigned = true) }
        verify(exactly = 0) { router.back() }
    }

    private fun repository(admitted: Boolean) = mockk<LedgerProposalRepository>(relaxed = true) {
        every { cancelUnsignedTransaction() } returns admitted
    }
}
