# Payment request representation

The last edited CrossPay unit owns its draft. A valid refreshed token price recomputes the linked field while preserving
that unit's entered value. Token-owned drafts keep token units; fiat-owned drafts keep fiat value. Quote Review remains
the explicit provider quote and authorization boundary. [Amount and slippage rules](SWAP-AMOUNTS.md) govern admission.

ZIP-321 memo data is a byte payload, not necessarily text. Text presentation strips trailing zero padding and decodes
strict UTF-8. The ZIP 302 no-memo marker is empty; opaque, reserved and undecodable content is identified as a non-text
memo with its decoded byte count. Review sends the original URI through the SDK proposal port. It does not replace bytes
with the disclosure text. [ZIP 321](https://zips.z.cash/zip-0321) · [ZIP 302](https://zips.z.cash/zip-0302)

An editable Send draft accepts only text. A non-text request goes directly to Review without creating a text-prefilled
Send screen behind it; a proposal failure or disabled-URI fallback displays a specific explanation and preserves the
existing draft. The SDK remains responsible for proposal validity. A non-text disclosure reports unrenderable bytes;
it does not assert that invalid UTF-8 is valid ZIP 302 text.

The pinned SDK `6.0.0` passes the URI to `TransactionRequest::from_uri` in its Rust proposal implementation. Source
inspection establishes that adapter contract; JVM regressions establish the app's exact URI handoff with SDK I/O
substituted. They do not execute a native binary-memo transaction.
[Pinned SDK adapter](https://github.com/zodl-inc/zcash-android-wallet-sdk/blob/f4da36be5ca78ca7fefe48cac0d3f6920a044cef/backend-lib/src/main/rust/lib.rs#L2652)

ZIP-321 builder/parser and proposal failure logs record fixed operation/category messages. Keep request URIs,
parsed payments, addresses, memo data and provider exception content out of those diagnostics, including debug builds.
The [regression procedure](DEVELOPMENT.md#review-boundary-regressions) names the real consumer tests and evidence limits.
