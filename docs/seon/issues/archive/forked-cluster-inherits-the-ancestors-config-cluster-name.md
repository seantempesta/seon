---
type: issue
status: resolved
severity: blocker
tags: [issue, config, cluster]
---

# Reconcile a forked cluster's own configuration identity

## Problem

A fork copies the ancestor database value, including its
`:seon.config/cluster` identity. Configuration reconciliation adopted only the
new desired identities, so an inherited row written under initialization
provenance could survive. `seon.config/effective` then returned `{}` for the
new cluster, and instrumented `result-caps` replaced the cause with a large
per-dial missing-key contract wall.

## Evidence

The original live probe is recorded in
[[docs/prds/sci-execution-runtime/research/session-curation-replay-mechanics-opus-2026-08-04]].
On scratch cluster `curation-opus`, the only config row named ancestor
`opuseffect0804`; reading `curation-opus` returned `{}`.

The dependency seam is Datahike branch creation in
`reference-code/datahike/src/datahike/versioning.cljc`: the branch begins from
the selected immutable database value, so inherited facts are expected input
to cluster boot. The fix is therefore at `seon.config/apply-compiled!`, which
now adopts every current config identity before exact reconciliation. Generic
reconciliation is unchanged.

`seon.config/effective` now returns the complete effective map or one declared
`:seon.config/missing-effective-error`. The error names the requested cluster
and available config identities in one line. `result-caps` accepts and returns
that error unchanged, so instrumentation cannot manufacture the per-key wall.

## Owner

`seon.config` owns config identity reconciliation, effective reads, and
result-cap derivation. The global schema registry owns the declared error
shape and its named render producers.

## Acceptance

Resolved by the path-limited fix that archives this note.

- `seon.config-test/apply-replaces-an-inherited-config-identity-regardless-of-provenance`
  constructs the inherited-provenance class, applies cluster `fork`, and
  proves that only `"fork"` remains and its result caps resolve.
- `seon.config-test/two-clusters-on-one-jvm-have-no-config-bleed` proves an
  unknown name returns the compact flat error and that `result-caps` preserves
  it exactly.
- `bin/test seon.config-test seon.bootstrap-test` passed 19 tests and 109
  assertions on 2026-08-04.
- The owner reported a clean isolated scratch boot of the current tree with
  agents and web ready, exercising configuration reconciliation at the real
  reset boundary.

The required changed-test selector was invoked for all changed source and
schema paths. Its broad dependency closure reached
`seon.dev.fresh-operator-test`, whose child remained silent for more than six
minutes; the selector was interrupted and its isolated root exited cleanly.
That foreign harness/slow-boundary defect does not change the focused or live
verdict.
