package app.hatch.logins

import mozilla.components.concept.storage.Address
import mozilla.components.concept.storage.CreditCard
import mozilla.components.concept.storage.CreditCardEntry
import mozilla.components.concept.storage.CreditCardNumber
import mozilla.components.concept.storage.CreditCardsAddressesStorageDelegate
import mozilla.components.concept.storage.ManagedKey

/**
 * `GeckoAutocompleteStorageDelegate` needs a credit-card/address half; Hatch stores neither. Every fetch is
 * empty, every save is dropped, and the engine never shows a card/address picker (the matching
 * `PromptFeature` flags stay at their `false` defaults). Interface verified against concept-storage
 * `CreditCardsAddressesStorage.kt:463-499` at FIREFOX_158_0_RELEASE.
 */
object NoCreditCardsAddressesDelegate : CreditCardsAddressesStorageDelegate {
    override suspend fun getOrGenerateKey(): ManagedKey = ManagedKey(key = "")

    override suspend fun decrypt(key: ManagedKey, encryptedCardNumber: CreditCardNumber.Encrypted): CreditCardNumber.Plaintext? = null

    override suspend fun onAddressesFetch(): List<Address> = emptyList()

    override suspend fun onAddressSave(address: Address) = Unit

    override suspend fun onCreditCardsFetch(): List<CreditCard> = emptyList()

    override suspend fun onCreditCardSave(creditCard: CreditCardEntry) = Unit
}
