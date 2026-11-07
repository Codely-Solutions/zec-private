# Support ZEC Private

Donations support the ZEC Private project. Use the matching asset and mainnet network, then check the complete destination address in your sending wallet before confirming.

## Zcash (ZEC)

Send ZEC to this project-controlled Unified Address using a wallet that supports Orchard:

```text
u16hcm5ln6c9yvj7snd3k2m9s5zlg2hntnqupzpl7r7xxqf23pf9dze7ja6782kjc454pu2kamk0xawul5zv9arugr797xfse385cgjmar
```

![Zcash mainnet donation address](donations/zec-address.png)

This Unified Address contains one Orchard receiver and no transparent receiver. Its mainnet encoding and Bech32m checksum were checked after decoding F4Jumble according to [ZIP 316](https://zips.z.cash/zip-0316).

## Monero (XMR)

Send XMR from an external Monero wallet to this mainnet subaddress:

```text
83Jji8QKKhRdeLZ6nALJaQhACiAMZ1MNZiFCwkTToD7KWEN3cCg8ePDbBRR6j7LM23gFttMQmSH9zUpbkn6N5pFxA6r9KtA
```

![Monero mainnet donation subaddress](donations/xmr-address.png)

The Monero network prefix and Keccak checksum were checked against the [Monero address format](https://github.com/monero-project/monero/blob/master/src/cryptonote_config.h). Listing this destination does not add Monero custody to ZEC Private.

Both QR codes contain only the exact address printed above, with no amount or payment identifier. They were generated locally and decoded with a separate QR implementation to verify an exact match. Address-format checks do not establish recipient ownership or guarantee transaction privacy. Any conversion through a swap provider has its own fees, availability and privacy considerations; these addresses do not establish that a conversion has completed.
