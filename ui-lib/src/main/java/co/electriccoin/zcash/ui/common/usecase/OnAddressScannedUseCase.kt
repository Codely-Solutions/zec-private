package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.screen.contact.AddZashiABContactArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.ADDRESS_BOOK
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.HOMEPAGE
import co.electriccoin.zcash.ui.screen.scan.ScanFlow.SEND
import co.electriccoin.zcash.ui.screen.send.Send

class OnAddressScannedUseCase(
    private val navigationRouter: NavigationRouter,
    private val prefillSend: PrefillSendUseCase,
    private val rejectIfSelectedAccountCannotSpend: RejectIfSelectedAccountCannotSpendUseCase,
    private val review: PreparePaymentReviewUseCase,
) {
    /**
     * An address scanned from home opens the send form, so an account that cannot spend gets the
     * unsupported-operation error in place of the scanner instead.
     */
    suspend operator fun invoke(
        address: String,
        addressType: AddressType,
        scanArgs: ScanArgs,
        sourceEntryId: String? = null,
    ): Boolean {
        require(addressType is AddressType.Valid)

        return when (scanArgs.flow) {
            SEND -> returnToSend(scanArgs, address, sourceEntryId)

            ADDRESS_BOOK -> navigationRouter.commitFrom(sourceEntryId) {
                NavigationCommand.Replace(listOf(AddZashiABContactArgs(address)))
            }

            HOMEPAGE -> openSend(address, addressType, sourceEntryId)
        }
    }

    private suspend fun returnToSend(scanArgs: ScanArgs, address: String, sourceEntryId: String?): Boolean {
        val sendEntryId = scanArgs.sendEntryId
        val owner = review.captureWalletOwner()
        if (sendEntryId == null || owner == null) return false
        return navigationRouter.commitFrom(sourceEntryId, owner) {
            prefillSend.forExistingSend(sendEntryId, PrefillSendData.FromAddressScan(address), owner)
            NavigationCommand.Back
        }
    }

    private suspend fun openSend(address: String, addressType: AddressType.Valid, sourceEntryId: String?): Boolean {
        var errorRoute: Any? = null
        val rejected = navigationRouter.whileAtEntry(sourceEntryId) {
            rejectIfSelectedAccountCannotSpend { errorRoute = it }
        } ?: return false
        val route = if (rejected) {
            errorRoute
        } else {
            val type = when (addressType) {
                AddressType.Tex -> cash.z.ecc.sdk.model.AddressType.TEX
                AddressType.Transparent -> cash.z.ecc.sdk.model.AddressType.TRANSPARENT
                else -> cash.z.ecc.sdk.model.AddressType.UNIFIED
            }
            Send(address, type)
        }
        return route != null && navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Replace(listOf(route)) }
    }
}
