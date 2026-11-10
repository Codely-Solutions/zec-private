package co.electriccoin.zcash.ui.common.usecase

import androidx.test.filters.SmallTest
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.zcash.ui.common.model.ufvk.UfvkFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The JVM tests fake the SDK, so these tests run the native [DerivationTool] against public
 * librustzcash viewing-key vectors. Orchard keys can omit transparent and Sapling components;
 * hardware-wallet exports can omit Sapling. No one's wallet key is involved.
 */
class TransparentAndOrchardKeySdkTest {
    private val key = UfvkFixtures.MAINNET_TRANSPARENT_AND_ORCHARD

    @Test
    @SmallTest
    fun theSdkDescribesAKeyWithoutSapling() =
        runBlocking {
            val components =
                assertNotNull(
                    DerivationTool
                        .getInstance()
                        .describeUnifiedFullViewingKey(UnifiedFullViewingKey(key), ZcashNetwork.Mainnet)
                )

            assertTrue(components.hasP2pkh)
            assertTrue(components.hasOrchard)
            assertFalse(components.hasSapling)
        }

    @Test
    @SmallTest
    fun theSdkDerivesAUnifiedAddressFromAKeyWithoutSapling() =
        runBlocking {
            val address =
                DerivationTool.getInstance().deriveUnifiedAddress(viewingKey = key, network = ZcashNetwork.Mainnet)

            assertTrue(address.startsWith(MAINNET_ADDRESS_PREFIX))
        }

    @Test
    @SmallTest
    fun theValidationAcceptsAKeyWithoutSapling() =
        runBlocking<Unit> {
            val result = ValidateUfvkUseCase(SdkUfvkDescriber())(key, ZcashNetwork.Mainnet)

            assertIs<UfvkValidation.Valid>(result)
        }

    @Test
    @SmallTest
    fun theSdkDescribesAnOrchardOnlyKey() =
        runBlocking {
            val components =
                assertNotNull(
                    DerivationTool
                        .getInstance()
                        .describeUnifiedFullViewingKey(
                            UnifiedFullViewingKey(UfvkFixtures.MAINNET_ORCHARD_ONLY),
                            ZcashNetwork.Mainnet
                        )
                )

            assertTrue(components.hasOrchard)
            assertFalse(components.hasP2pkh)
            assertFalse(components.hasSapling)
        }

    @Test
    @SmallTest
    fun theSdkDerivesAUnifiedAddressFromAnOrchardOnlyKey() =
        runBlocking {
            val address =
                DerivationTool.getInstance().deriveUnifiedAddress(
                    viewingKey = UfvkFixtures.MAINNET_ORCHARD_ONLY,
                    network = ZcashNetwork.Mainnet
                )

            assertTrue(address.startsWith(MAINNET_ADDRESS_PREFIX))
        }

    private companion object {
        const val MAINNET_ADDRESS_PREFIX = "u1"
    }
}
