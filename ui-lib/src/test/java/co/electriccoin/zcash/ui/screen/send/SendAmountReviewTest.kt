package co.electriccoin.zcash.ui.screen.send

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.balances.BalanceWidgetState
import co.electriccoin.zcash.ui.screen.send.model.AmountField
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.MemoState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendAddressBookState
import co.electriccoin.zcash.ui.screen.send.model.SendStage
import co.electriccoin.zcash.ui.screen.send.view.Send
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h720dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SendAmountReviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun texHidesAndOmitsOversizedMemoAndRejectsZero() {
        val account = mockk<WalletAccount>(relaxed = true) {
            every { spendableShieldedBalance } returns Zatoshi(1_000_000)
            every { canSpend(any()) } returns true
        }
        var recipient by mutableStateOf(RecipientAddressState.new("fixture-shielded-recipient", AddressType.Unified))
        var memo by mutableStateOf(MemoState.new(""))
        var amount by mutableStateOf(zecAmount("0.001", 100_000))
        val reviewed = mutableListOf<ZecSend>()
        compose.setContent {
            ZcashTheme {
                Send(
                    balanceWidgetState = BalanceWidgetState.loadingPreview,
                    sendStage = SendStage.Form,
                    onCreateZecSend = { reviewed += it },
                    onBack = {},
                    onQrScannerOpen = {},
                    hasCameraFeature = false,
                    recipientAddressState = recipient,
                    onRecipientAddressChange = {},
                    setAmountState = {},
                    amountState = amount,
                    setMemoState = { memo = it },
                    memoState = memo,
                    selectedAccount = account,
                    exchangeRateState = ExchangeRateState.OptedOut,
                    sendAddressBookState = SendAddressBookState(
                        SendAddressBookState.Mode.PICK_FROM_ADDRESS_BOOK,
                        false,
                        {},
                    ),
                    zashiMainTopAppBarState = ZashiMainTopAppBarState.previewLoading,
                )
            }
        }

        val reviewButton = compose.onNodeWithTag(SendTag.SEND_FORM_BUTTON)
        val memoField = compose.onNodeWithTag(SendTag.SEND_MEMO_FIELD)
        val oversized = "x".repeat(Memo.MAX_MEMO_LENGTH_BYTES + 1)
        reviewButton.assertIsEnabled()
        memoField.assertIsEnabled().performTextReplacement(oversized)
        compose.runOnIdle { assertIs<MemoState.TooLong>(memo) }
        reviewButton.assertIsNotEnabled()

        compose.runOnIdle { recipient = RecipientAddressState.new(TEX_RECIPIENT, AddressType.Tex) }
        memoField.assertIsNotEnabled()
        compose.onNodeWithText(oversized).assertDoesNotExist()
        reviewButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            val send = reviewed.single()
            assertIs<WalletAddress.Tex>(send.destination)
            assertEquals(TEX_RECIPIENT, send.destination.address)
            assertEquals("", send.memo.value)
            assertEquals(Zatoshi(100_000), send.amount)
            assertIs<MemoState.TooLong>(memo)
            amount = zecAmount("0", 0)
        }
        reviewButton.assertIsNotEnabled()

        compose.runOnIdle {
            recipient = RecipientAddressState.new(SAPLING_RECIPIENT, AddressType.Shielded)
        }
        reviewButton.assertIsNotEnabled()
        memoField.assertIsEnabled().performTextReplacement("For the memo")
        reviewButton.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(Zatoshi(0), reviewed.last().amount)
            assertEquals("For the memo", reviewed.last().memo.value)
        }
    }

    private fun zecAmount(text: String, amount: Long) =
        AmountState.Valid(stringRes(text), stringRes(""), AmountField.ZEC, Zatoshi(amount))

    private companion object {
        // https://zips.z.cash/zip-0320#reference-implementation
        const val TEX_RECIPIENT = "tex1s2rt77ggv6q989lr49rkgzmh5slsksa9khdgte"
        // Sapling receiver from row 0 of the Zcash unified address test vectors.
        const val SAPLING_RECIPIENT = "zs1mrhc9y7jdh5r9ece8u5khgvj9kg0zgkxzdduyv0whkg7lkcrkx5xqem3e48avjq9wn2rukydkwn"
    }
}
