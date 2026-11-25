package co.electriccoin.zcash.ui.screen.scankeystone

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.common.UnavailableWalletAction
import co.electriccoin.zcash.ui.screen.scankeystone.view.ScanKeystoneView
import co.electriccoin.zcash.ui.screen.scankeystone.viewmodel.ScanKeystonePCZTViewModel
import co.electriccoin.zcash.ui.util.SettingsUtil
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun WrapScanKeystonePCZTRequest(args: ScanKeystonePCZTRequest, sourceEntryId: String) {
    val navigationRouter = koinInject<NavigationRouter>()
    val walletViewModel = koinActivityViewModel<WalletViewModel>()
    val synchronizer = walletViewModel.synchronizer.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()
    val viewModel = koinViewModel<ScanKeystonePCZTViewModel> { parametersOf(sourceEntryId) }
    KeystoneSignatureScanner(args, viewModel, synchronizer != null) {
        scope.launch { navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Back } }
    }
}

@Composable
internal fun KeystoneSignatureScanner(
    args: ScanKeystonePCZTRequest,
    viewModel: ScanKeystonePCZTViewModel,
    synchronizerReady: Boolean,
    goBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val validationState by viewModel.validationState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canScan by remember(viewModel, args) { viewModel.observeCanScan(args) }
        .collectAsStateWithLifecycle(initialValue = viewModel.canScan(args))

    BackHandler(onBack = goBack)

    if (!canScan) {
        UnavailableWalletAction(
            screenTitle = stringRes(R.string.keystone_signWith_signTransaction),
            title = stringRes(R.string.private_keystone_unavailable_title),
            message = stringRes(R.string.private_keystone_unavailable_message),
            action = ButtonState(text = stringRes(R.string.general_back), onClick = goBack),
        )
    } else if (!synchronizerReady) {
        CircularScreenProgressIndicator()
    } else {
        ScanKeystoneView(
            snackbarHostState = snackbarHostState,
            onBack = goBack,
            onScan = {
                viewModel.onScanned(it, args)
            },
            onOpenSettings = {
                runCatching {
                    context.startActivity(SettingsUtil.newSettingsIntent(context.packageName))
                }.onFailure {
                    // This case should not really happen, as the Settings app should be available on every
                    // Android device, but rather handle it.
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = context.getString(R.string.scan_settings_open_failed)
                        )
                    }
                }
            },
            onScanStateChange = {},
            validationResult = validationState,
            state = state,
        )
    }
}
