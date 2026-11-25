@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.advancedsettings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AdvancedSettingsScreen(entryId: String) {
    val viewModel = koinViewModel<AdvancedSettingsVM> { parametersOf(entryId) }
    val state = viewModel.state.collectAsStateWithLifecycle().value
    BackHandler { viewModel.onBack() }
    AdvancedSettings(state = state)
}

@Serializable
data object AdvancedSettingsArgs
