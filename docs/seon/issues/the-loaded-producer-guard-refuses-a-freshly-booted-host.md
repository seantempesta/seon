---
type: issue
status: open
severity: blocker
tags: [issue, publication, operator, wave/publication-velocity, class/tools]
---

# The loaded-producer guard refuses a freshly booted host

## Observation — 2026-09-21 ~22:00 UTC, four `bin/seon reset --force` runs

Republish, refork and start succeed (default alive, serving HEAD); the
adopt phase refuses: "The live host's loaded producers do not match the
requested toolchain" with EVERY producer namespace in
`:seon.source/producer-mismatch` although each recorded producer digest
equals the current input (`data/operator/operations/reset-adopt-56195.log`).
The aggregate `toolchain-digest` differs while per-producer evidence says
nothing changed. `d8921fbd4` made both sides use one derivation
(`seon.cluster/loaded-producer-digest`); the refusal persists, so the two
callers feed different inputs: `require-loaded-producers!` at
`src/seon/cluster.clj:2308` passes the cached prospective manifest and
`(:seon.fn/root roots)`, the recorder (`record-loaded-producers!`) the boot
artifact's manifest and `fs/source-directory`; and the fallback "all
producers when the digests differ" converts an unexplained aggregate into
a refusal of everything. Owner: the publication-dissolution lane
(`3ac00fb8e`), at resume. Regression: a host that just booted from the
tree adopts a publication of the same tree.

## Morning evidence and repair

The retained refusal contains no `:seon.source/loaded-producer-digest` at
all. It does not establish an aggregate mismatch. `ef6dee041` preserves a
refused generation read instead of converting it into “all producers
mismatch”, and canonicalizes relative roots before formatting the host
transition. The recorder additionally carries the booted instance's
projection on its retained database, so its caller's schema binding cannot
choose that observation's world.

The real one-JVM boot regression recorded/read the generation and passed
the guard when recording ran with no caller projection, then reached
`development-source-refresh!`. Adoption failed later while compiling
`seon.dev.docstring/check-file`'s Var-based contract; the runner's re-arm
failed on the same contract. Evidence:
`tmp/publication-dissolution/morning-publication-head-retry.log` and
`morning-host-retry-threads.txt` (main thread inside development indexing,
after guard admission). This is a separate boundary, not a green adoption.

Recurring coverage is `seon.cluster.publication-host-test` (record with a
caller projection, assert the carried projection is the booted instance's,
then publish the same tree) and `seon.cluster.publication-adoption-test`
(the complete development adoption, long and explicitly selected). The
new explicit-projection assertion is not yet verified: subsequent fast
admission is blocked by the SCI sweep's unstorable required candidates
member. Status remains open; final scratch-root adoption remains owed.

## Slice 0 — 2026-09-22

The owner-approved redesign removes the guard rather than repairing its
recording. Slice 0 deletes the four guard functions and every caller, the
boot-time input read and recording transaction, and the 13 schema declarations
used only by the guard. Existing boot/publication and adoption tests retain
their behavioral assertions without recording a generation first.

RESET NEEDED: the guard's attributes are removed. The issue stays open until
the orchestrator resets and measures first adoption on the fresh host; code
removal alone is not live acceptance. Exact verification and deletion counts
are in the [slice 0 landing note](../../prds/steward-platform/research/one-jvm-redesign-2026-09-22.md).
