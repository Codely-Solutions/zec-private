package co.electriccoin.zcash.app

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.usecase.DeleteSharedQRImagesUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** The host stages only these synthetic files, then force-stops the disposable app before entry. */
class ProjectReviewSensitiveCacheTest {
    @Test
    fun priorProcessViewingImageIsRemovedWithoutTouchingUnrelatedCache() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.personal.zecprivate.testnet.foss.debug")
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(ZcashNetwork.fromResources(context) == ZcashNetwork.Testnet)
        check(InstrumentationRegistry.getArguments().getString("isolatedTestnet") == "valueless-coins-only")
        val directory = File(context.cacheDir, "zodl_qr_images")
        val sensitive = File(directory, "zodl_viewing_key_qr_review_interrupted.png")
        val unrelated = File(directory, "review_keep.txt")
        check(sensitive.readText() == "synthetic-viewing-information")
        check(unrelated.readText() == "unrelated-cache-control")
        try {
            DeleteSharedQRImagesUseCase(context).deleteViewingKeyImages()
            check(!sensitive.exists())
            check(unrelated.readText() == "unrelated-cache-control")
        } finally {
            unrelated.delete()
            sensitive.delete()
        }
    }
}
