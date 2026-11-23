package co.electriccoin.zcash.ui.screen.viewingkey.importvk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import co.electriccoin.zcash.ui.design.theme.AppearanceMode
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.viewingkey.BuildConfig

/** Renders the viewing-key import form with synthetic preview state and inert callbacks. */
class FeatureViewingKeyReviewActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG)
        val state = when (intent.getStringExtra("screen") ?: "view-only-import-empty") {
            "view-only-import-empty" -> ImportVKState.preview
            "view-only-import-filled" -> ImportVKState.previewFilled
            else -> error("Unknown viewing-key review scene")
        }
        val theme = intent.getStringExtra("theme") ?: "light"
        require(theme in setOf("light", "dark", "oled"))
        setContent {
            ZcashTheme(
                appearanceMode = if (theme == "light") AppearanceMode.LIGHT else AppearanceMode.DARK,
                isOledEnabled = theme == "oled",
            ) {
                Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    ImportVKView(state)
                }
            }
        }
    }
}
