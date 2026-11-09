package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.model.AddressBookContact
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.EnhancedABContact
import co.electriccoin.zcash.ui.common.repository.GiftCardSecretFixture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * A gift card link, in any disguise of [GiftCardSecretFixture], must never be saved as a swap contact, from where it
 * would reach the swap provider. Ordinary addresses keep the uniqueness check.
 */
class ValidateSwapABContactAddressUseCaseTest {
    private val btc = SwapAssetTestFixture.blockchain("btc")

    private val existing =
        EnhancedABContact(
            contact =
                AddressBookContact(
                    name = "Existing",
                    address = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
                    lastUpdated = Instant.fromEpochMilliseconds(0),
                    chain = "btc"
                ),
            blockchain = btc
        )

    private val addressBookRepository =
        mockk<AddressBookRepository> {
            every { contacts } returns MutableStateFlow(listOf(existing))
        }

    private val validate = ValidateSwapABContactAddressUseCase(addressBookRepository = addressBookRepository)

    @Test
    fun giftCardLinkIsRejected() =
        runTest {
            GiftCardSecretFixture.all.forEach { (name, link) ->
                assertEquals(ContactAddressValidationResult.GiftCardLink, validate(link, btc), name)
                assertEquals(ContactAddressValidationResult.GiftCardLink, validate(link, null), name)
            }
            verify(exactly = 0) { addressBookRepository.contacts }
        }

    @Test
    fun ordinaryAddressesKeepTheUniquenessCheck() =
        runTest {
            assertEquals(ContactAddressValidationResult.Valid, validate("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", btc))
            assertEquals(ContactAddressValidationResult.NotUnique, validate(existing.address, btc))
            assertEquals(ContactAddressValidationResult.Valid, validate(existing.address, btc, exclude = existing))
        }

    @Test
    fun invalidAddressAndMissingNetworkAreRejectedBeforeContactLookup() = runTest {
        assertEquals(ContactAddressValidationResult.Invalid, validate("bc1qinvalid", btc))
        assertEquals(ContactAddressValidationResult.Invalid, validate(existing.address, null))
        assertEquals(
            ContactAddressValidationResult.Invalid,
            validate("ethereum:0x1111111111111111111111111111111111111111@1", btc)
        )
        verify(exactly = 0) { addressBookRepository.contacts }
    }

    @Test
    fun paymentUriUsesParsedAddressForDuplicateCheck() = runTest {
        assertEquals(ContactAddressValidationResult.NotUnique, validate("bitcoin:${existing.address}", btc))
    }

    @Test
    fun invalidLegacyContactsRemainUnchangedDuringValidation() = runTest {
        val legacy = existing.copy(contact = existing.contact.copy(address = "legacy-invalid-address"))
        val contacts = MutableStateFlow(listOf(legacy))
        every { addressBookRepository.contacts } returns contacts
        assertEquals(ContactAddressValidationResult.Invalid, validate(legacy.address, btc))
        assertEquals(listOf(legacy), contacts.value)
        verify(exactly = 0) { addressBookRepository.saveContact(any(), any(), any()) }
        verify(exactly = 0) { addressBookRepository.updateContact(any(), any(), any(), any()) }
    }
}
