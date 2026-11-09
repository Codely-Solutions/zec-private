package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stacked = maxWidth / LocalDensity.current.fontScale < 320.dp
        Column {
            links.forEachIndexed { index, link ->
                if (index > 0) ZashiHorizontalDivider(thickness = 2.dp)
                TransactionDetailRowSurface(
                    onClick = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    if (stacked) {
                        Column(modifier = Modifier.weight(1f)) {
                            ExplorerLabel(link)
                            ExplorerId(link, textAlign = TextAlign.Start)
                        }
                        Spacer(Modifier.width(12.dp))
                        ExplorerActions(link)
                    } else {
                        ExplorerLabel(link, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Row(
                            modifier = Modifier.weight(1.7f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ExplorerId(link, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                            ExplorerActions(link)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerLabel(link: TransactionExplorerLink, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier,
        text = link.label.getValue(),
        style = ZashiTypography.textSm,
        color = ZashiColors.Text.textTertiary,
    )
}

@Composable
private fun ExplorerId(
    link: TransactionExplorerLink,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = stringResByTransactionId(link.transactionId, abbreviated = true) orHiddenString
            stringRes(co.electriccoin.zcash.ui.design.R.string.general_hideBalancesMost),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = ZashiTypography.textSm,
        fontWeight = FontWeight.Medium,
        textAlign = textAlign,
        color = ZashiColors.Text.textPrimary,
    )
}

@Composable
private fun ExplorerActions(link: TransactionExplorerLink) {
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
