package co.electriccoin.zcash.ui.common.model

import android.content.Context
import co.electriccoin.zcash.spackle.FirebaseTestLabUtil
import co.electriccoin.zcash.ui.BuildConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals

class TestLabWalletFixturePolicyTest {
    @Test
    fun onlyDebugTestnetUsesTheTestServiceSignal() {
        val eligible = BuildConfig.DEBUG && BuildConfig.FLAVOR_network == "zcashtestnet"
        val context = mockk<Context> {
            every { applicationContext } returns this
        }

        mockkObject(FirebaseTestLabUtil)
        try {
            every { FirebaseTestLabUtil.isFirebaseTestLab(context) } returnsMany listOf(true, false)
            assertEquals(eligible, VersionInfo.testLabFixturesEnabled(context))
            assertEquals(false, VersionInfo.testLabFixturesEnabled(context))
            verify(exactly = if (eligible) 2 else 0) { FirebaseTestLabUtil.isFirebaseTestLab(context) }
        } finally {
            unmockkObject(FirebaseTestLabUtil)
        }
    }
}
