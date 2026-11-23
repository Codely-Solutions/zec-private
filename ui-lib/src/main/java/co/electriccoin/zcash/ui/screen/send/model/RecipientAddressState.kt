package co.electriccoin.zcash.ui.screen.send.model

import cash.z.ecc.android.sdk.type.AddressType

data class RecipientAddressState(
    val address: String,
    val type: AddressType?
) {
    val supportsMemo: Boolean
        get() = type == AddressType.Shielded || type == AddressType.Unified

    val canEditMemo: Boolean
        get() = address.isEmpty() || type is AddressType.Invalid || supportsMemo

    companion object {
        private const val KEY_ADDRESS = "address" // $NON-NLS
        private const val KEY_TYPE = "type" // $NON-NLS
        private const val KEY_INVALID_REASON = "invalid_reason" // $NON-NLS
        private const val TYPE_INVALID = "invalid" // $NON-NLS
        private const val TYPE_SHIELDED = "shielded" // $NON-NLS
        private const val TYPE_TRANSPARENT = "transparent" // $NON-NLS
        private const val TYPE_TEX = "tex" // $NON-NLS
        private const val TYPE_UNIFIED = "unified" // $NON-NLS

        fun new(
            address: String,
            type: AddressType? = null
        ): RecipientAddressState = RecipientAddressState(address, type)

        internal fun fromSavedState(state: HashMap<String, String>): RecipientAddressState =
            RecipientAddressState(
                address = state.getValue(KEY_ADDRESS),
                type = when (state[KEY_TYPE]) {
                    TYPE_INVALID -> AddressType.Invalid(state.getValue(KEY_INVALID_REASON))
                    TYPE_SHIELDED -> AddressType.Shielded
                    TYPE_UNIFIED -> AddressType.Unified
                    TYPE_TRANSPARENT -> AddressType.Transparent
                    TYPE_TEX -> AddressType.Tex
                    else -> null
                }
            )
    }

    internal fun toSavedState(): HashMap<String, String> = hashMapOf(KEY_ADDRESS to address).apply {
        when (type) {
            is AddressType.Invalid -> {
                put(KEY_INVALID_REASON, type.reason)
                put(KEY_TYPE, TYPE_INVALID)
            }

            AddressType.Shielded -> put(KEY_TYPE, TYPE_SHIELDED)
            AddressType.Unified -> put(KEY_TYPE, TYPE_UNIFIED)
            AddressType.Transparent -> put(KEY_TYPE, TYPE_TRANSPARENT)
            AddressType.Tex -> put(KEY_TYPE, TYPE_TEX)
            null -> Unit
        }
    }
}
