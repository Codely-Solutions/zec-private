# Parallel emulator checks

Use `admit_emulator_batch.py` when two independent, required core/UI checks can overlap. Supply each worker's prepared job specification and immutable artifact manifest:

```text
python -B .private/admit_emulator_batch.py --worker core CORE_JOB_JSON CORE_MANIFEST_JSON --worker ui UI_JOB_JSON UI_MANIFEST_JSON
```

Use the existing single-worker launcher when only one profile needs execution. Keep funding, sending, confirmation, update and recovery steps within the same lifecycle sequential. Do not repeat still-valid acceptance just to fill a second slot.

Verify new or failing diagnostic scenarios alone first. Pair a lifecycle that can claim faucet coins or submit a transaction only with proven independent checks; otherwise use separate admissions so a peer failure cannot discard its funded fixture. Separate accepted profiles retain their own exact artifact and receipt bindings.

The launcher admits at most two workers through the existing shared guard. Two workers require 16 GiB available RAM, receive a combined 12 GiB process-memory cap and reserve 17 GiB against an 18 GiB cumulative storage cap. Each guest uses at most 3 GiB RAM and two virtual CPUs. ADB, console/gRPC ports, writable state and manifest snapshots are separate; the verified system image is shared. A worker failure or deadline cancels its peer, and closing the enclosing Windows Job stops descendants.

The current default output is root-relative; account for it in the owning job and move completed records only after execution ends. Results, exact input hashes, cleanup receipts and bounded per-worker evidence go into the parent project's `emulator-batch-records` directory. A batch passes only when every worker passes. The batch launcher does not grant release acceptance: the publication controller must still validate each required profile's current inputs, APK identity and result. Changed runner inputs invalidate older core acceptance and require the existing candidate/release gates.

`python -B .private/admit_emulator_batch.py --smoke` boots two blank guests, verifies separate state and deliberately fails one to check peer cancellation and cleanup. It installs no wallet APK, claims no faucet coins and is not product acceptance.
