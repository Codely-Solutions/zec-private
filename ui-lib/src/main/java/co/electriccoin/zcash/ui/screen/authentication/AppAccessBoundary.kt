package co.electriccoin.zcash.ui.screen.authentication

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import co.electriccoin.zcash.ui.common.model.AppAccessSession

@Composable
internal fun AppAccessBoundary(
    session: AppAccessSession,
    lockedContent: @Composable () -> Unit,
    walletContent: @Composable (AppAccessSession.Grant) -> Unit
) {
    val grant by session.grant.collectAsState()
    val currentGrant = grant
    if (currentGrant != null) {
        walletContent(currentGrant)
    } else {
        lockedContent()
    }
}
