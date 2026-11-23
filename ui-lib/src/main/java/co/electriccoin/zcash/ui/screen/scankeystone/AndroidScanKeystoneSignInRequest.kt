package co.electriccoin.zcash.ui.screen.scankeystone

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.screen.scankeystone.view.ScanKeystoneView
import co.electriccoin.zcash.ui.screen.scankeystone.viewmodel.ScanKeystoneSignInRequestViewModel
import co.electriccoin.zcash.ui.util.SettingsUtil
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun WrapScanKeystoneSignInRequest() {
    SecureScreen()
    val navigationRouter = koinInject<NavigationRouter>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val walletViewModel = koinActivityViewModel<WalletViewModel>()
    val viewModel = koinViewModel<ScanKeystoneSignInRequestViewModel>()
    val synchronizer = walletViewModel.synchronizer.collectAsStateWithLifecycle().value
    val validationResult by viewModel.validationState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var presentationId by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(viewModel) {
        val id = viewModel.beginPresentation()
        presentationId = id
        onPauseOrDispose {
            viewModel.endPresentation(id)
            presentationId = null
        }
    }

    BackHandler {
        navigationRouter.back()
    }

    val activePresentation = presentationId
    if (synchronizer == null || activePresentation == null) {
        // TODO [#1146]: Consider moving CircularScreenProgressIndicator from Android layer to View layer
        // TODO [#1146]: Improve this by allowing screen composition and updating it after the data is available
        // TODO [#1146]: https://github.com/Electric-Coin-Company/zashi-android/issues/1146
        CircularScreenProgressIndicator()
    } else {
        key(activePresentation) {
            ScanKeystoneView(
                snackbarHostState = snackbarHostState,
                onBack = { navigationRouter.back() },
                onScan = { viewModel.onScanned(it, activePresentation) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(SettingsUtil.newSettingsIntent(context.packageName))
                    }.onFailure {
                        // Report when the system settings screen is unavailable.
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = context.getString(R.string.scan_settings_open_failed)
                            )
                        }
                    }
                },
                onScanStateChange = {},
                validationResult = validationResult,
                state = state,
            )
        }
    }
}
