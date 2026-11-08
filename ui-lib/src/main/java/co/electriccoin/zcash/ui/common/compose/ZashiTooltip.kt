package co.electriccoin.zcash.ui.common.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipScope.ZashiTooltip(
    title: StringResource,
    message: StringResource,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainTooltip(
        modifier =
            modifier
                .clickable(onClick = onDismissRequest),
        shape = RoundedCornerShape(8.dp),
        containerColor = ZashiColors.HintTooltips.surfacePrimary,
        maxWidth = Dp.Unspecified,
        caretShape = TooltipDefaults.caretShape(DpSize(16.dp, 8.dp))
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, bottom = 12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    color = ZashiColors.HintTooltips.defaultFg,
                    style = ZashiTypography.textMd,
                    text = title.getValue()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    color = ZashiColors.HintTooltips.defaultFg,
                    style = ZashiTypography.textSm,
                    text = message.getValue()
                )
            }
            IconButton(onClick = onDismissRequest) {
                Icon(
                    painter = painterResource(R.drawable.ic_exchange_rate_unavailable_dialog_close),
                    contentDescription = stringResource(R.string.tooltip_close_content_description),
                    tint = ZashiColors.HintTooltips.defaultFg
                )
            }
        }
    }
}
