# Composable Wallet Foundation

ZEC Private selects its shipping swap provider in `app/.../ZecPrivateProductModule.kt`. The existing application module remains the product entry point. There is one shipping wallet engine and one swap provider.

## Dependency direction

`app` selects integrations → `ui-lib` coordinates Zcash/NEAR and retained presentation → `wallet-foundation-lib` supplies engine-independent values, deposit capability validation and request ownership.

The foundation is a Kotlin JVM library. Its production contracts use Kotlin and Java math/time only. It has no Android, Zcash SDK, NEAR DTO, HTTP, dependency-injection or UI dependency. The Android app already supplies the platform support required by its Java time use. The alternative compositions in its tests exercise external-only funding and memo-bearing flexible deposits without an engine. They establish structural reuse, not settlement support.

## Current composition

| Flow | Funding and owner | Approval and recovery |
|---|---|---|
| Native ZEC receive/send | Existing account, synchronizer and proposal integrations | Existing seed custody, biometrics, local signing or hardware signing; no shared secret format |
| External asset → ZEC | External deposit; fresh shielded recipient; NEAR flexible-input quote | Review precedes showing deposit instructions; accepted metadata keeps the provider and deposit reference |
| ZEC → external asset | Local transaction; fresh refund; exact input | Zcash proposal remains opaque to shared contracts; execution retains the account-specific signer |
| CrossPay | Local transaction; exact output | Fixed requested destination amount; same signing and metadata owners |
| Dismiss/requote | Repository cancels jobs; foundation request identities reject stale results | Clearing advances the identity before cancellation; no acceptance or funding occurs |
| Legacy swap status/refund | Existing V2/V3 readers, original provider identity | NEAR schema-1 recovery uses its original deposit reference; unknown providers fail before network access and preserve the record |

`ZecSwapComposition` maps the current product's quote into exact asset/network-tagged deposit instructions. It rejects incompatible funding directions, providers, precision and expiry. Provider mapping, response-echo/slippage checks and gift-secret rejection stay in their existing integrations. Zcash quote fee calculations live under `model/zcash`, outside `SwapQuote` and NEAR's quote implementation. Zero affiliate fees remain a product invariant.

Hardware, view-only, gifts and native receive/recovery keep their existing owners and navigation. Their chain-specific semantics are deliberate. The foundation does not reinterpret seeds or add a general transaction representation.

## Adding a flow or component

1. Specify funding, quote modes, network/asset precision, required memo/tag, expiry, approval and recovery reference semantics. Reject unsupported combinations before approval.
2. Supply a provider adapter through the product assembly. Translate its DTOs and transport errors at that boundary. Keep endpoint selection and Tor policy inside the integration.
3. Reuse the existing wallet capability only when it matches the requested network and custody rules. A different engine owns its keys, storage, recovery and prepared transaction types.
4. Give asynchronous work an owner. Invalidate outstanding request IDs before clearing or replacement; serialize the identity check and publication under the same owner lock.
5. Preserve old-provider recovery for existing records. Introduce a new explicit persisted schema only when required, with readers and installed-update evidence. An unknown identity must never choose a default provider.
6. Select dependencies at build time and inspect the final APK. A future XMR app needs separate identity, data and recovery; a second APK is not created by this phase.

XMR Private and THORChain remain paper scenarios. Monero custody, synchronization and recovery require a separate integration; an external XMR destination is not an owned wallet. A memo-bearing THORChain-style deposit requires an adapter and flow declaring that capability. The present ZEC product rejects such instructions. No current route availability or network support is claimed.

## Upstream and validation

Keep stable discovery enabled. Carry upstream wallet fixes into the existing account/proposal/synchronizer owners, provider fixes into the NEAR adapter and DTOs, presentation fixes into retained screens, and composition changes into the product mapping. The maintained patch contains the extraction; sensitive changes to the foundation are review-gated along with shared wiring. Validate a disposable integration candidate before publication.

Foundation runtime changes invalidate both real wallet and quote evidence. Fingerprints remain conservative across the complete runtime graph. Unit tests cover precision, flow incompatibility, stale publication, fee calculations and synthetic legacy persistence. Required real testnet and unfunded production quote acceptance remain separate. Production artifacts are promoted after acceptance without rebuilding; signing secrets remain outside build jobs.
