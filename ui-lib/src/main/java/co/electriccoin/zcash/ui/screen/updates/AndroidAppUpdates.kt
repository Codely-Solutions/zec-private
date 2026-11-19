package co.electriccoin.zcash.ui.screen.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.about.AboutArgs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
private fun ObserveForegroundUpdates(repository: AppUpdateRepository) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                repository.check()
                delay(repository.nextCheckDelayMillis())
            }
        }
    }
}

@Composable
internal fun AndroidAppUpdates() {
    val repository = koinInject<AppUpdateRepository>()
    if (!repository.enabled) return
    val router = koinInject<NavigationRouter>()
    val state by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    ObserveForegroundUpdates(repository)
    val available = (state as? AppUpdateState.Available)?.let { repository.currentOffer() }
    AppUpdateContent(
        checking = state is AppUpdateState.Checking,
        current = state is AppUpdateState.Current,
        failed = state is AppUpdateState.Failed,
        availableTag = available?.tag,
        onCheck = { scope.launch { repository.check(manual = true) } },
        onOpenRelease = {
            available?.takeIf { it == repository.currentOffer() }?.let { router.forward(ExternalUrl(it.releasePage)) }
        },
        onObtainium = { router.forward(ExternalUrl(OBTAINIUM_GUIDE_URL)) },
    )
}

@Composable
internal fun rememberAppUpdateNotice(blocked: Boolean): AppUpdateRelease? {
    val repository = koinInject<AppUpdateRepository>()
    if (!repository.enabled) return null
    val state by repository.state.collectAsStateWithLifecycle()
    if (!blocked) ObserveForegroundUpdates(repository)
    return if (blocked || state !is AppUpdateState.Available) null else repository.cachedNotice()
}

@Composable
internal fun AndroidAppUpdateNotice(release: AppUpdateRelease) {
    val repository = koinInject<AppUpdateRepository>()
    val router = koinInject<NavigationRouter>()
    val scope = rememberCoroutineScope()
    AppUpdateNotice(
        tag = release.tag,
        onOpen = { router.forward(AboutArgs) },
        onDismiss = { scope.launch { repository.dismiss(release.versionCode) } },
    )
}

private const val OBTAINIUM_GUIDE_URL =
    "https://github.com/Codely-Solutions/zec-private/blob/main/docs/WALLET-GUIDE.md#automatic-updates"
