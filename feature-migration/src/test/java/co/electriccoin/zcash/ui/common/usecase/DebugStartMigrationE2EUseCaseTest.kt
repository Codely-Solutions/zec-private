package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.migration.BuildConfig
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DebugStartMigrationE2EUseCaseTest {
    @Test
    fun onlyDebugTestnetHasFixtureAuthority() = runTest {
        val expected = BuildConfig.DEBUG && BuildConfig.FLAVOR.contains("testnet", ignoreCase = true)
        assertEquals(expected, DebugStartMigrationE2EUseCase.isAllowed)
        if (!expected) {
            val sdk = mockk<GetOrchardMigrationSdkUseCase>()
            val fixture = DebugStartMigrationE2EUseCase(
                sdk, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(),
            )
            assertFailsWith<IllegalStateException> { fixture() }
            coVerify(exactly = 0) { sdk() }
        }
    }
}
