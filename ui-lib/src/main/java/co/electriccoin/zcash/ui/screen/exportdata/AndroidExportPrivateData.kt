package co.electriccoin.zcash.ui.screen.exportdata

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.usecase.ExportPrivateDataUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.screen.exportdata.view.ExportPrivateData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File

@Composable
internal fun WrapExportPrivateData(entryId: String) {
    val walletViewModel = koinActivityViewModel<WalletViewModel>()
    val synchronizer = walletViewModel.synchronizer.collectAsStateWithLifecycle().value
    val navigationRouter = koinInject<NavigationRouter>()
    val export = koinInject<ExportPrivateDataUseCase>()
    val accounts = koinInject<GetWalletAccountsUseCase>().observe().collectAsStateWithLifecycle().value
    val context = LocalActivity.current.applicationContext

    val close = { navigationRouter.stageResultFrom(entryId) { NavigationCommand.Back } }
    BackHandler(onBack = close)
    if (synchronizer == null || accounts == null) {
        CircularScreenProgressIndicator()
    } else if (accounts.firstOrNull { it.isSelected }?.isSpendable != true) {
        LaunchedEffect(entryId) { close() }
    } else {
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        ExportPrivateData(
            snackbarHostState = snackbar,
            onBack = close,
            onAgree = {},
            onConfirm = {
                scope.launch {
                    try {
                        val shared = export(
                            isSourceCurrent = { navigationRouter.isAtEntry(entryId) },
                            isChooserAllowed = { navigationRouter.isForegroundAtEntry(entryId) },
                        ) {
                            File(synchronizer.getExistingDataDbFilePath(context, ZcashNetwork.fromResources(context)))
                        }
                        if (shared) close()
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
