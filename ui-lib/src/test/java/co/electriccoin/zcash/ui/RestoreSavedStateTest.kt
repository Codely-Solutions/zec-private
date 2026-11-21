package co.electriccoin.zcash.ui

import android.os.Parcel
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.screen.restore.date.RestoreDateArgs
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreEstimationArgs
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeight
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSession
import co.electriccoin.zcash.ui.screen.restore.tor.RestoreTorArgs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreSavedStateTest {
    @Test
    fun navigationSavesOnlyOpaqueSessionIdAndProcessLossReturnsToSeedEntry() {
        val first = newController().also { it.graph = restoreGraph(it) }
        val phrase = Mnemonics.MnemonicCode(ByteArray(32)).words.joinToString(" ") { it.concatToString() }
        val seed = SeedPhrase.new(phrase)
        val session = RestoreSession()
        val sessionId = session.start(seed)
        first.navigate(RestoreHeight(sessionId))
        first.navigate(RestoreDateArgs(sessionId))
        first.navigate(RestoreEstimationArgs(sessionId, BLOCK_HEIGHT))
        first.navigate(RestoreTorArgs(sessionId, BLOCK_HEIGHT))

        val parcel = Parcel.obtain()
        val restored = newController()
        try {
            parcel.writeBundle(assertNotNull(first.saveState()))
            val serialized = parcel.marshall()
            assertFalse(serialized.toString(Charsets.UTF_8).contains(phrase))
            assertFalse(serialized.toString(Charsets.UTF_16LE).contains(phrase))
            parcel.setDataPosition(0)
            restored.restoreState(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
        restored.graph = restoreGraph(restored)
        assertEquals(sessionId, restored.getBackStackEntry(RestoreHeight(sessionId)).toRoute<RestoreHeight>().sessionId)
        assertEquals(
            sessionId,
            restored.getBackStackEntry(RestoreDateArgs(sessionId)).toRoute<RestoreDateArgs>().sessionId
        )
        assertEquals(
            sessionId,
            restored.getBackStackEntry(RestoreEstimationArgs(sessionId, BLOCK_HEIGHT))
                .toRoute<RestoreEstimationArgs>().sessionId
        )
        assertEquals(RestoreTorArgs(sessionId, BLOCK_HEIGHT), restored.currentBackStackEntry?.toRoute<RestoreTorArgs>())
        assertEquals(seed, session.resolve(sessionId))
        assertNull(RestoreSession().resolve(sessionId))

        restored.returnToRestoreSeed()

        assertEquals(RestoreSeedArgs, restored.currentBackStackEntry?.toRoute<RestoreSeedArgs>())
    }

    private fun newController() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
    }

    private fun restoreGraph(controller: NavHostController) =
        controller.createGraph(startDestination = RestoreSeedArgs) {
            composable<RestoreSeedArgs> { }
            composable<RestoreHeight> { }
            composable<RestoreDateArgs> { }
            composable<RestoreEstimationArgs> { }
            composable<RestoreTorArgs> { }
        }

    private companion object {
        const val BLOCK_HEIGHT = 500_000L
    }
}
