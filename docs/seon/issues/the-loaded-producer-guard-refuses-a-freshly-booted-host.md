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
