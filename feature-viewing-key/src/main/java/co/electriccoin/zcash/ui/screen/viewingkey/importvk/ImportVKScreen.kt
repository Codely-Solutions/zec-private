package co.electriccoin.zcash.ui.screen.viewingkey.importvk

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.compose.SecureScreen
import co.electriccoin.zcash.ui.design.component.ZashiConfirmationBottomSheet
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun ImportVKScreen(sourceEntryId: String) {
    SecureScreen()
    val vm = koinViewModel<ImportVKVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val invalidKeySheet by vm.invalidKeySheet.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    ImportVKView(state)
    ZashiConfirmationBottomSheet(invalidKeySheet)
}

/**
 * The screen that takes the full viewing key to import, pasted or typed. Like every route of the
 * flow it carries no key: routes are saved into the instance state, so the key lives only in
 * [co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepository] and the view models.
 */
@Serializable
data object ImportVKArgs
