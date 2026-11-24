package co.electriccoin.zcash.ui.screen.migration.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.design.component.BlankBgScaffold
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ZashiButton
import co.electriccoin.zcash.ui.design.component.ZashiSmallTopAppBar
import co.electriccoin.zcash.ui.design.component.ZashiTopAppBarCloseNavigation
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.scaffoldPadding
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.R as DesignR

@Composable
internal fun MigrationUnavailable(message: StringResource, onBack: () -> Unit, onRetry: () -> Unit) {
    BlankBgScaffold(
        topBar = {
            ZashiSmallTopAppBar(navigationAction = { ZashiTopAppBarCloseNavigation(onBack = onBack) })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().scaffoldPadding(padding).verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = message.getValue(),
                style = ZashiTypography.textLg,
                color = ZashiColors.Text.textPrimary,
            )
            Spacer(Modifier.height(16.dp))
            ZashiButton(
                modifier = Modifier.fillMaxWidth(),
                state = ButtonState(text = stringRes(DesignR.string.migration_common_tryAgain), onClick = onRetry),
            )
        }
    }
}
