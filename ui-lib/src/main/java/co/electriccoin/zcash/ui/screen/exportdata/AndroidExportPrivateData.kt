package co.electriccoin.zcash.ui.screen.exportdata

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.usecase.ExportPrivateDataUseCase
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.screen.exportdata.view.ExportPrivateData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

@Composable
internal fun WrapExportPrivateData() {
    val walletViewModel = koinActivityViewModel<WalletViewModel>()
    val synchronizer = walletViewModel.synchronizer.collectAsStateWithLifecycle().value
    val navigationRouter = koinInject<NavigationRouter>()
    val export = koinInject<ExportPrivateDataUseCase>()
    val context = LocalActivity.current.applicationContext

    BackHandler { navigationRouter.back() }
    if (synchronizer == null) {
        CircularScreenProgressIndicator()
    } else {
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        ExportPrivateData(
            snackbarHostState = snackbar,
            onBack = { navigationRouter.back() },
            onAgree = {},
            onConfirm = {
                scope.launch {
                    try {
                        val shared = export {
                            File(synchronizer.getExistingDataDbFilePath(context, ZcashNetwork.fromResources(context)))
                        }
                        if (shared) navigationRouter.back()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        snackbar.showSnackbar(context.getString(R.string.export_data_unable_to_share))
                    }
                }
            },
        )
    }
}
