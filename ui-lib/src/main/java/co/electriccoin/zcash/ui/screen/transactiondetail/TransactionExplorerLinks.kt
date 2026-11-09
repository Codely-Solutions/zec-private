package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.TransactionExplorer
import co.electriccoin.zcash.ui.design.theme.colors.ZashiColors
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByTransactionId
import co.electriccoin.zcash.ui.screen.ExternalUrl

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
    if (links.isEmpty()) return
    Column {
        links.forEach { link ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val open = link.onOpen
                if (open != null) {
                    TextButton(onClick = open, modifier = Modifier.weight(1f)) {
                        Column {
                            Text(stringRes(R.string.private_view_in_explorer, link.label).getValue())
                            if (links.size > 1) {
                                Text(stringResByTransactionId(link.transactionId, abbreviated = true).getValue())
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(link.label.getValue())
                        Text(stringResByTransactionId(link.transactionId, abbreviated = true).getValue())
                    }
                }
                IconButton(onClick = link.onCopy) {
                    Icon(painterResource(R.drawable.ic_transaction_detail_info_copy),
                        tint = ZashiColors.Text.textTertiary,
                        contentDescription = stringRes(R.string.private_copy_explorer_id, link.label).getValue())
                }
            }
        }
    }
}
