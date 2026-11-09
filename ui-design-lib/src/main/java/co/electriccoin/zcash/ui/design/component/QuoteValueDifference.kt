package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class QuoteValueDifferenceState(
    val percentage: StringResource,
    val explanation: StringResource,
    val isNegative: Boolean,
    val validUntilMillis: Long? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuoteValueDifference(state: QuoteValueDifferenceState) {
    var isCurrent by remember(state) {
        mutableStateOf(state.validUntilMillis?.let { it > System.currentTimeMillis() } ?: true)
    }
    LaunchedEffect(state) {
        state.validUntilMillis?.let {
            delay((it - System.currentTimeMillis()).coerceAtLeast(0))
            isCurrent = false
        }
    }
    if (!isCurrent) return
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val value = state.percentage.getValue()
    val explanation = state.explanation.getValue()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        state = tooltip,
        tooltip = { PlainTooltip { Text(explanation, style = MaterialTheme.typography.bodySmall) } },
    ) {
        Text(
            text = "($value)",
            style = ZashiTypography.textXxs,
            color = if (state.isNegative) ZashiColors.Text.textError else ZashiColors.Text.textTertiary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .semantics { contentDescription = "$value. $explanation" }
                .clickable(role = Role.Button) { scope.launch { tooltip.show() } },
        )
    }
}
