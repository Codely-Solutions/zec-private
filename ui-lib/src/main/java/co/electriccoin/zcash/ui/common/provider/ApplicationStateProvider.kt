package co.electriccoin.zcash.ui.common.provider

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

interface ApplicationStateProvider {
    val isInForeground: Flow<Boolean>

    fun onApplicationLifecycleChanged(event: Lifecycle.Event)

    fun observeOnForeground(): Flow<Unit>
}

class ApplicationStateProviderImpl : ApplicationStateProvider {
    // WorkManager can start the process without an Activity. Only ProcessLifecycleOwner's
    // real foreground transition enables continuous SDK sync for the wallet.
    private val foreground = MutableStateFlow(false)

    override val isInForeground: Flow<Boolean> = foreground.asStateFlow()

    override fun onApplicationLifecycleChanged(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> foreground.value = true
            Lifecycle.Event.ON_STOP -> foreground.value = false
            else -> Unit
        }
    }

    override fun observeOnForeground(): Flow<Unit> =
        flow {
            var previous = foreground.value
            foreground.collect { current ->
                if (current && !previous) emit(Unit)
                previous = current
            }
        }
}
