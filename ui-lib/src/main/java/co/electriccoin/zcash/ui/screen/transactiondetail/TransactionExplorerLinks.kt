package co.electriccoin.zcash.ui.screen.transactiondetail

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.TransactionExplorer
import co.electriccoin.zcash.ui.design.component.ExternalLinkRow
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.ExternalUrl

data class TransactionExplorerLink(
    val label: StringResource,
    val transactionId: String,
    val onOpen: (() -> Unit)?,
)

fun explorerLinks(
    chain: String,
    ids: List<String>,
    label: StringResource,
    testnet: Boolean,
    router: NavigationRouter,
): List<TransactionExplorerLink> = ids.filter { it.isNotBlank() }.distinct().map { id ->
    val open = TransactionExplorer.url(chain, id, testnet)?.let { url ->
        { router.forward(ExternalUrl(url)) }
    }
    TransactionExplorerLink(label, id, open)
}

@Composable
fun TransactionExplorerLinks(links: List<TransactionExplorerLink>) {
    val destinations = links.filter { it.onOpen != null }.distinctBy { it.label to it.transactionId }
    if (destinations.isEmpty()) return
    Column {
        destinations.groupBy { it.label }.forEach { (label, transactions) ->
            transactions.forEachIndexed { index, link ->
                val title = if (transactions.size == 1) {
                    stringRes(R.string.private_view_in_explorer, label)
                } else {
                    stringRes(R.string.private_view_in_explorer_numbered, label, index + 1)
                }
                ExternalLinkRow(label = title.getValue(), onClick = checkNotNull(link.onOpen))
            }
        }
    }
}
