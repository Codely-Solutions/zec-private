package co.electriccoin.zcash.ui.screen.updates

import android.content.Context
import co.electriccoin.zcash.spackle.getPackageInfoCompat
import co.electriccoin.zcash.spackle.versionCodeCompat
import co.electriccoin.zcash.ui.BuildConfig
import co.electriccoin.zcash.ui.common.provider.HttpClientProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object Current : AppUpdateState
    data class Available(val release: AppUpdateRelease, val dismissed: Boolean = false) : AppUpdateState
    data object Failed : AppUpdateState
}

class AppUpdateRepository(context: Context, private val httpClientProvider: HttpClientProvider) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("app_update_discovery", Context.MODE_PRIVATE)
    private val installedCode =
        appContext.packageManager.getPackageInfoCompat(appContext.packageName, 0L).versionCodeCompat
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AppUpdateState>(
        cachedAvailability() ?: AppUpdateState.Idle
    )
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    val enabled: Boolean =
        !BuildConfig.DEBUG && BuildConfig.FLAVOR_network == "zcashmainnet" &&
            appContext.packageName == "com.personal.zecprivate.foss"

    fun cachedNotice(): AppUpdateRelease? =
        UpdatePolicy.notice(cachedRelease(), installedCode, preferences.getInt("dismissed", 0))

    suspend fun check(manual: Boolean = false) {
        if (!enabled) return
        // Drop overlapping checks rather than queueing repeated manual requests.
        if (!mutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            val last = if (preferences.contains("last_check")) preferences.getLong("last_check", 0) else null
            if (!UpdatePolicy.shouldCheck(now, last, manual)) return
            preferences.edit().putLong("last_check", now).apply()
            val previous = cachedAvailability()
            mutableState.value = AppUpdateState.Checking
            try {
                val release = readLatestRelease()
                preferences.edit().putString("release", encodeCache(release)).apply()
                mutableState.value = cachedAvailability() ?: AppUpdateState.Current
            } catch (cancelled: CancellationException) {
                val editor = preferences.edit()
                if (last == null) editor.remove("last_check") else editor.putLong("last_check", last)
                editor.apply()
                mutableState.value = previous ?: AppUpdateState.Idle
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = previous ?: AppUpdateState.Failed
            }
        } finally {
            mutex.unlock()
        }
    }

    suspend fun dismiss(versionCode: Int) = mutex.withLock {
        preferences.edit().putInt("dismissed", versionCode).apply()
        mutableState.value = cachedAvailability() ?: AppUpdateState.Idle
    }

    /** Explicit network-bound read for isolated acceptance; never downloads or installs APKs. */
    suspend fun readLatestRelease(): AppUpdateRelease = withTimeoutOrNull(REQUEST_DEADLINE_MILLIS) {
        httpClientProvider.create().use { client ->
            val response = client.get(LATEST_RELEASE_URL) {
                header("Accept", "application/vnd.github+json")
                header("User-Agent", "ZEC-Private")
                header("X-GitHub-Api-Version", "2022-11-28")
            }
            require(response.status.value == HTTP_OK)
            UpdatePolicy.parse(response.bodyAsText())
        }
    } ?: throw IOException("Update check deadline exceeded")

    private fun cachedRelease(): AppUpdateRelease? =
        try {
            preferences.getString("release", null)?.let(UpdatePolicy::parse)
        } catch (_: Exception) {
            null
        }

    private fun cachedAvailability(): AppUpdateState.Available? =
        UpdatePolicy.availability(cachedRelease(), installedCode, preferences.getInt("dismissed", 0))?.let {
            AppUpdateState.Available(it.release, it.dismissed)
        }

    // Reuse the same validation for persisted metadata; URLs are always regenerated from the tag.
    private fun encodeCache(release: AppUpdateRelease): String =
        buildJsonObject {
            put("draft", false)
            put("prerelease", false)
            put("tag_name", release.tag)
            put("body", "Android versionCode: ${release.versionCode}")
            put(
                "assets",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", APK_NAME)
                            put("size", release.size)
                            put("digest", release.digest?.let(::JsonPrimitive) ?: JsonNull)
                            put("browser_download_url", release.downloadUrl)
                        }
                    )
                }
            )
        }.toString()

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/Sammy-Lastre/zec-private/releases/latest"
        const val REQUEST_DEADLINE_MILLIS = 30_000L
        const val HTTP_OK = 200
    }
}
