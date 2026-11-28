package co.electriccoin.zcash.ui.screen.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.AppearanceModeStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsOledEnabledStorageProvider
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * The app-wide appearance the root [co.electriccoin.zcash.ui.design.theme.ZcashTheme] renders with. Scoped to
 * the activity, since the theme wraps the whole content tree rather than any single screen.
 */
internal class ThemeVM(
    appearanceModeStorageProvider: AppearanceModeStorageProvider,
    isOledEnabledStorageProvider: IsOledEnabledStorageProvider,
) : ViewModel() {
    /**
     * Both stored theme values as one emission, so [appearanceMode], [isOledEnabled] and [isThemeResolved]
     * all derive from a single upstream read and can never disagree about whether it has landed yet. Null
     * until that read completes.
     *
     * The read is bounded in both directions, because the splash screen is held until it produces something:
     * a throwing preference store falls back to [DEFAULT_THEME_APPEARANCE] instead of killing the coroutine,
     * and a store that simply never answers is given [THEME_READ_TIMEOUT] before the same fallback is
     * emitted. A genuine value arriving afterwards still replaces the fallback.
     *
     * After a throwing store, reconnect both observations. Keep the last resolved pair while
     * reconnecting, and space out retries if storage remains unavailable.
     */
    private val theme: StateFlow<ThemeAppearance?> =
        channelFlow {
            var resolved = false
            val fallback =
                launch {
                    delay(THEME_READ_TIMEOUT)
                    Twig.error { "Stored app theme not read in time, falling back to the default appearance" }
                    resolved = true
                    send(DEFAULT_THEME_APPEARANCE)
                }

            combine(
                appearanceModeStorageProvider.observe(),
                isOledEnabledStorageProvider.observe()
            ) { appearanceMode, isOledEnabled ->
                ThemeAppearance(
                    appearanceMode = appearanceMode ?: AppearanceMode.SYSTEM,
                    isOledEnabled = isOledEnabled == true
                )
            }.retryWhen { error, attempt ->
                if (attempt == 0L) {
                    Twig.error { "Stored app theme observation failed (${error.javaClass.simpleName}); reconnecting" }
                }
                if (!resolved) emit(DEFAULT_THEME_APPEARANCE)
                delay(if (attempt == 0L) THEME_READ_TIMEOUT else THEME_RECONNECT_INTERVAL)
                true
            }.collect {
                fallback.cancel()
                resolved = true
                send(it)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            null
        )

    /**
     * A flow of the user's chosen [AppearanceMode]. A never-chosen preference resolves to [AppearanceMode.SYSTEM].
     */
    val appearanceMode: StateFlow<AppearanceMode> =
        theme
            .map { it?.appearanceMode ?: AppearanceMode.SYSTEM }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                AppearanceMode.SYSTEM
            )

    /**
     * A flow of whether pure black (OLED) should be used whenever [appearanceMode] resolves to dark.
     */
    val isOledEnabled: StateFlow<Boolean> =
        theme
            .map { it?.isOledEnabled == true }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                false
            )

    /**
     * Whether [appearanceMode] and [isOledEnabled] carry the stored values instead of their seeded defaults.
     * The splash screen is held while this is false, so the first composed frame already renders the user's
     * own appearance rather than flashing System/Classic Dark first. Shared eagerly because that gate reads
     * the value without subscribing to it. Guaranteed to turn true within [THEME_READ_TIMEOUT] even when the
     * preference store never answers, so a broken store costs the user the wrong theme rather than the app.
     */
    val isThemeResolved: StateFlow<Boolean> =
        theme
            .map { it != null }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                false
            )
}

private data class ThemeAppearance(
    val appearanceMode: AppearanceMode,
    val isOledEnabled: Boolean,
)

/**
 * How long the stored theme read is given before the splash screen is released with the default appearance.
 * Short enough not to read as a hang, long enough for a healthy preference store to win the race.
 */
internal val THEME_READ_TIMEOUT = 2.seconds
internal val THEME_RECONNECT_INTERVAL = 30.seconds

private val DEFAULT_THEME_APPEARANCE =
    ThemeAppearance(
        appearanceMode = AppearanceMode.SYSTEM,
        isOledEnabled = false
    )
