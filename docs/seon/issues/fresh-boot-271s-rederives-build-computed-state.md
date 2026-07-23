---
type: issue
status: active
tags: [issue, runtime]
---

# Fresh boot takes 271s, re-deriving state the build already computed

## Measured breakdown (predfix live proof, 2026-07-23)

Total fresh reset → pod ready: 271s. Pod log span 191s
(logs/operator-predfix/pod/823236b9-ad3a-434b-a5d2-39c171bd1671.log):

- ~81s boot-time corpus indexing (`var->fn-row` over the compiled
  corpus) BEFORE the first initialization page; the 97 pages then take
  only ~8s.
- ~35s gap between session acquired and committed projection
  acquisition started (unlogged work — identify it).
- ~46s committed projection construction (schema rows → Malli
  projection) before instrumentation.
- Instrumentation of 925 fns is fast once construction completes.
- ~80s outside the pod log (writer boot + paged init + watcher build).

## Hypotheses (design inputs, not conclusions)

- The P1b build sidecars already carry the analyzer function inventory;
  boot re-analyzes the same corpus from source. Boot should CONSUME
  build-computed artifacts, not re-derive them.
- The committed projection is a pure derivation of committed facts —
  R21's "cache measured expensive derivations" case, basis/digest
  keyed. A warm projection cache keyed by (basis, graph digest,
  schema fingerprint) could make reconstruction O(changed).
- Owner constraint (R42/R27): no guessed budgets; whatever remains slow
  must be observable (it now is) and justified as real work.

## Acceptance

- Named owner design (research file) ruling what boot derives vs
  consumes precomputed, with the derive-don't-store boundary respected
  (caches are keyed derivations, never a second authority).
- Fresh reset → pod ready measured under a target the owner blesses
  from the design's numbers.
