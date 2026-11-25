package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.PendingNavigation
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.screen.taxexport.TaxExport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigateToTaxExportUseCaseTest {
    @Test
    fun authenticatedAdmissionRequiresTheOriginalEntryAndGrant() = runTest {
        val entry = "advanced-settings"
        val active = MutableStateFlow(ActiveNavigationEntry(entry, null))
        val pending = PendingNavigation()
        val router = mockk<NavigationRouter> {
            every { activeEntry } returns active
            every { pendingNavigation } returns pending
        }
        val access = AppAccessSession().apply { unlock() }
        val biometrics = mockk<BiometricRepository>()
        val openTaxExport = NavigateToTaxExportUseCase(router, biometrics, access)
        coEvery { biometrics.requestBiometrics(any()) } answers {
            active.value = ActiveNavigationEntry("scanner", null)
        }
        openTaxExport(entry)
        assertTrue(pending.commands.value.isEmpty())

        active.value = ActiveNavigationEntry(entry, null)
        coEvery { biometrics.requestBiometrics(any()) } returns Unit
        openTaxExport(entry)
        val handoff = checkNotNull(pending.commands.value[entry])
        assertTrue(handoff.isStillAllowed())
        assertEquals(NavigationCommand.Forward(listOf(TaxExport)), handoff.buildCommand())

        access.lock()
        access.unlock()
        assertFalse(handoff.isStillAllowed())
    }
}
