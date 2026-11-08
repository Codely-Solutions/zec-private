@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.about

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.electriccoin.zcash.configuration.api.ConfigurationProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.compose.LocalActivity
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.about.view.About
import co.electriccoin.zcash.ui.screen.support.model.ConfigInfo
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import co.electriccoin.zcash.ui.screen.updates.AndroidAppUpdates

@Composable
internal fun AboutScreen() {
    val activity = LocalActivity.current
    val navigationRouter = koinInject<NavigationRouter>()
    val androidConfigurationProvider = koinInject<ConfigurationProvider>()
    val configInfo = ConfigInfo.new(androidConfigurationProvider)
    val versionInfo = VersionInfo.new(activity.applicationContext)
    LaunchedEffect(Unit) { androidConfigurationProvider.hintToRefresh() }
    BackHandler { navigationRouter.back() }
    About(
        onBack = { navigationRouter.back() },
        configInfo = configInfo,
        versionInfo = versionInfo,
        updates = { AndroidAppUpdates() },
        onPrivacyPolicy = {
            navigationRouter.forward(
                ExternalUrl("https://github.com/Sammy-Lastre/zec-private/blob/main/docs/PRIVACY.md")
            )
        },
        onTermsOfUse = {
            navigationRouter.forward(
                ExternalUrl("https://github.com/Sammy-Lastre/zec-private/blob/main/docs/USE.md")
            )
        }
    )
}

@Serializable
@Suppress("MatchingDeclarationName")
data object AboutArgs
