package co.electriccoin.zcash.ui.screen.migration.keystonescan

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.design.component.CircularScreenProgressIndicator
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.component.MigrationUnavailable
import co.electriccoin.zcash.ui.screen.migration.component.MigrationFailureBottomSheet
import co.electriccoin.zcash.ui.screen.scankeystone.view.ScanKeystoneView
import co.electriccoin.zcash.ui.util.SettingsUtil
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import co.electriccoin.zcash.ui.design.R as DesignR

@Serializable
data class MigrationKeystoneScanArgs(
    val mode: MigrationMode,
    val accountKeyId: String? = null,
)

@Composable
fun MigrationKeystoneScanScreen(args: MigrationKeystoneScanArgs) {
    val vm = koinViewModel<MigrationKeystoneScanVM> { parametersOf(args) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val readiness by vm.isScanReady.collectAsStateWithLifecycle()
    if (readiness != true) {
        BackHandler { vm.recoverFromHome() }
        if (readiness == null) {
            CircularScreenProgressIndicator()
        } else {
            MigrationUnavailable(
                message = stringRes(DesignR.string.migrationScheduled_signatureRequired),
                onBack = vm::recoverFromHome,
                onRetry = vm::recoverFromHome,
                retryLabel = stringRes(R.string.general_back),
            )
        }
        return
    }
    val validationState by vm.validationState.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val failureSheet by vm.failureSheet.collectAsStateWithLifecycle()

    BackHandler { vm.onBack() }

    ScanKeystoneView(
        snackbarHostState = snackbarHostState,
        onBack = { vm.onBack() },
        onScan = { vm.onScanned(it) },
        onOpenSettings = {
            runCatching {
                context.startActivity(SettingsUtil.newSettingsIntent(context.packageName))
            }.onFailure {
                // This case should not really happen, as the Settings app should be available
                // on every Android device, but rather handle it.
                scope.launch {
                    snackbarHostState.showSnackbar(message = context.getString(R.string.scan_settings_open_failed))
                }
            }
        },
        onScanStateChange = {},
        validationResult = validationState,
        state = state,
    )
    MigrationFailureBottomSheet(failureSheet)
}
