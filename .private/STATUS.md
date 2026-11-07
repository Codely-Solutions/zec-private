# Revision 13 — donations validated, awaiting signing

Candidate 42367044863 at `d12dea751a4dbab29671acab394777a7c613774c` passed all 1,724 tests and required release checks. Optional shielded donations use normal review/authentication, default to a zero saved suggestion and never spend automatically. Unsupported multi-recipient payment requests are rejected before signing. Actual network costs remain visible.

The isolated lifecycle executed a real shielded testnet donation, cancellation/review, installed revision-12 update, restart, recipient recovery and scoped Tor interruption/recovery. Core and UI APKs were reused from `686a9f10b80fd44b9467c092ace4cdcd80767b1a` after exact relevant-input equality established that later corrections changed tests only. Each receipt retains its actual executed source and APK digest. Production quotes remain unfunded.

Upstream discovery, package identity, ARM64-only production, x86_64 emulator support and build/signing separation remain. The validated unsigned artifact will be promoted without rebuilding. Production ARM execution, physical signing/biometric UI and funded production settlement are outside this evidence. XMR/THORChain implementation remains unplanned.
