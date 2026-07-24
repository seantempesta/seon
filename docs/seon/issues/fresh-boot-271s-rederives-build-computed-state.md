---
type: issue
status: open
severity: friction
tags: [issue, runtime]
---

# Fresh boot takes 271s, re-deriving state the build already computed

## Overnight triage — 2026-07-23

**FOLD-INTO-UNIT — R45 S-ladder.** `df2cb508a` delivered 14× projection
construction and reuse, but the latest fresh readiness remained about 314
seconds. Build-emitted startup data plus the apply/start split remain R45 work.

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

## Verified root causes (design accepted 2026-07-23:

research/boot-time-design-2026-07-23.md — supersedes the hypotheses)

- build-projection is QUADRATIC (schema.cljc:656-846): 3,298 per-row
  contract asserts each re-walk the full population (predicate
  symbols, bound-forms postwalk, fresh registry per call at
  :438-489). Malli compile itself is fast (0.37s to instrument 925
  fns once the projection exists).
- Fresh boot builds the full projection TWICE: the schemagate
  prevalidation gate (client.cljs ~:1844) discards its build; admission
  rebuilds the identical population later.
- The 35s gap = reconcile-config! + ensure-initial-agent! with zero
  log lines (an R42 observability gap — instrument first).
- Sidecar consumption (the original hypothesis) is DEMOTED to D3,
  re-evaluated after D1/D2 land; index-core! still re-derives
  structure the digest-guarded program-sources.edn carries.
- Corrected shares: writer boots in 0.2s; cluster wipe ~24s; shadow
  builds ~13s; pod bundle load ~25s; 97 pages 16s.

## Plan (ranked, target ≤90s fresh reset → pod ready)

D1 de-quadratic the one owner (precomputed registry/compiled-forms
into the per-row assert; fingerprint byte-equality regression) → D2
fingerprint-guarded reuse of the boot-frame projection at admission
(cache can only SKIP, never change) → D4 instrument the 35s gap →
D3 program-rows sidecar re-evaluated after re-measurement. Sol lane
"bootfast" runs D1+D2+D4 after the fixseed lane frees schema.cljc,
BEFORE the checkpoint (every live proof then boots faster).

## Acceptance

- Named owner design (research file) ruling what boot derives vs
  consumes precomputed, with the derive-don't-store boundary respected
  (caches are keyed derivations, never a second authority).
- Fresh reset → pod ready measured under a target the owner blesses
  from the design's numbers.
