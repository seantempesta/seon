---
type: issue
status: open
severity: friction
tags: [seon.operator, seon.cluster.registry, seon.blob, collect, gc-storage, schema, class/shape-instead-of-fact]
opened: 2026-09-17
---

# Blob roots are derived from digest SHAPE, not from a declared fact

## Problem

`seon.cluster.registry/blob-digest-attributes` and
`seon.operator/digest-attributes` both answer "which attributes name a blob
stored in this store?" by walking each attribute's schema form and asking
whether it resolves to `:seon.blob/digest`
(`src/seon/cluster/registry.clj:330-351`, `src/seon/operator.clj:727-756`).

That is a SHAPE test standing in for a missing fact. Several attributes are
64-hex content digests that are not konserve keys at all. Measured on a
freshly published, freshly forked cluster on 2026-09-17 (scratch root,
cluster `lane`): of 33 digests derived this way, exactly ONE was a stored
blob; the other 32 were `:seon.db/read-result-digest` values — read-evidence
content digests that were never written to the store.

Consequences:

- In the MARK (`referenced-blobs`), the whitelist carries keys that do not
  exist. Harmless today, but it is a silent lie about what is reachable.
- In VERIFICATION (`seon.operator/root-verification`), the shape test made a
  collection that had lost nothing refuse by naming a digest that was never
  stored. That was found the moment root verification began to run
  unconditionally (see
  [the collector completeness note](../../prds/steward-platform/research/collector-completeness-2026-09-17.md)).

Verification now works around it: it verifies only digests the store HELD
before the sweep and counts the rest as
`:seon.operator.collect/unstored-digests`. That is honest and it is a
differential, but the underlying question — "does this attribute name a blob
this store stores?" — is still answered by a shape rather than by a fact.

## Wanted

The fact declared where blobs are declared: a schema property on the
attribute (or on `:seon.blob/digest`'s own declaration) saying that its values
are konserve blob keys, and BOTH derivations reading that property. Then

- the mark's whitelist is exactly the blobs that exist,
- verification needs no differential to stay honest, and
- `unstored-digests` becomes a defect report rather than an expected count.

Two derivations of one question must not disagree, and neither should be a
shape test (AGENTS.md §2.2: facts over inference).
