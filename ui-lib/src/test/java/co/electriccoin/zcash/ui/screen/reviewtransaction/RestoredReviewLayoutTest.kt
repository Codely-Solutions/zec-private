package co.electriccoin.zcash.ui.screen.reviewtransaction

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoredReviewLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun renderedRecoveryAtLargeTextShowsTheExplanationAndAnActionableBackButton() {
        val resources = RuntimeEnvironment.getApplication().resources
        var backCount = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ZcashTheme {
                    ReviewTransactionView(unavailableReview { backCount++ })
                }
            }
        }

        compose.onNodeWithText(resources.getString(R.string.private_review_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.private_review_unavailable_message)).assertIsDisplayed()
        compose.onNodeWithText(resources.getString(R.string.general_back), ignoreCase = true)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, backCount) }
    }
}
