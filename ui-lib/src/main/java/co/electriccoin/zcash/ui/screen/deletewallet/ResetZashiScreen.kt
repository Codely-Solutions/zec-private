package co.electriccoin.zcash.ui.screen.deletewallet

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ResetZashiScreen() {
    val vm = koinViewModel<ResetZashiVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) {
        BackHandler { it.onBack() }
        ResetZashiView(it)
    }
}

@Composable
internal fun ResetFromWalletRecovery(
    canReview: () -> Boolean,
    awaitHost: suspend () -> Boolean,
    onBack: () -> Unit,
) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val vm = koinViewModel<ResetZashiVM>(viewModelStoreOwner = owner)
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) {
        val recovery = vm.bindRecovery(it, canReview, awaitHost, onBack)
        BackHandler { recovery.onBack() }
        ResetZashiView(recovery)
    }
}

internal suspend fun awaitWalletRecoveryHost(
    lifecycle: Lifecycle,
    appState: ApplicationStateProvider,
    permits: () -> Boolean,
): Boolean = combine(lifecycle.currentStateFlow, appState.isInForeground) { state, foreground ->
    val liveHost = foreground && state != Lifecycle.State.DESTROYED
    if (liveHost && permits()) true.takeIf { state.isAtLeast(Lifecycle.State.RESUMED) } else false
}.filterNotNull().first()

@Serializable
data object ResetZashiArgs
