package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.TransactionExplorer
import co.electriccoin.zcash.ui.design.component.ZashiHorizontalDivider
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.orHiddenString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.ExternalUrl
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRow
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailRowSurface

data class TransactionExplorerLink(
    val label: StringResource,
    val transactionId: String,
    val onOpen: (() -> Unit)?,
    val onCopy: () -> Unit,
)

fun explorerLinks(
    chain: String,
    ids: List<String>,
    label: StringResource,
    testnet: Boolean,
    router: NavigationRouter,
    copy: (String) -> Unit,
): List<TransactionExplorerLink> = ids.filter { it.isNotBlank() }.distinct().map { id ->
    val open = TransactionExplorer.url(chain, id, testnet)?.let { url ->
        { router.forward(ExternalUrl(url)) }
    }
    TransactionExplorerLink(label, id, open, { copy(id) })
}

@Composable
fun TransactionExplorerLinks(links: List<TransactionExplorerLink>) {
    links.forEachIndexed { index, link ->
        if (index > 0) ZashiHorizontalDivider(thickness = 2.dp)
        TransactionDetailRowSurface(
            onClick = null,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = link.label.getValue(),
                style = ZashiTypography.textSm,
                color = ZashiColors.Text.textTertiary,
            )
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1.7f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResByTransactionId(link.transactionId, abbreviated = true) orHiddenString
                        stringRes(co.electriccoin.zcash.ui.design.R.string.general_hideBalancesMost),
                    style = ZashiTypography.textSm,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.End,
                    color = ZashiColors.Text.textPrimary,
                )
                IconButton(onClick = link.onCopy, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_transaction_detail_info_copy),
                        tint = ZashiColors.Text.textTertiary,
                        contentDescription = stringRes(R.string.private_copy_explorer_id, link.label).getValue(),
                    )
                }
                link.onOpen?.let { open ->
                    IconButton(onClick = open, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            tint = ZashiColors.Text.textTertiary,
                            contentDescription = stringRes(R.string.private_view_in_explorer, link.label).getValue(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionDetailIdRows(
    links: List<TransactionExplorerLink>,
    fallback: TransactionDetailInfoRowState,
) {
    if (links.isEmpty()) {
        TransactionDetailInfoRow(state = fallback, modifier = Modifier.fillMaxWidth())
    } else {
        TransactionExplorerLinks(links)
    }
}
