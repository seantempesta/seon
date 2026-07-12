---
type: issue
status: resolved
tags: [issue, database]
---

# Datahike fork: 3-clause query silently returns #{} on a valid clause order

Found 2026-07-02 during the seon-skills live-verification pass (every example
eval'd against the live default pod). **Root-caused and fixed in the fork
2026-07-02** — awaiting the sha bump + pod/wire-server restart for the live
proof (see Status).

## Symptom

A specific 3-clause combination silently returns `#{}` instead of the correct
rows: an id-lookup clause (no tx var) placed BEFORE a wildcard-value
tx-binding clause that a third clause joins on. Reordering the clauses returns
the correct rows. Reproduced reliably on the live pod, on a hermetic
in-memory CLJS db in the pod, and on the JVM with
`DATAHIKE_QUERY_PLANNER=true`.

```clojure
;; FAILS (returned #{}):
[:find ?at ?tx
 :where [?e :src/id "src-1"]        ; id-lookup first ("most selective first")
        [?e :src/title _ ?tx]       ; binds ?tx from the datom's tx slot
        [?tx :db/txInstant ?at]]    ; joins on ?tx
;; WORKS: same query with the first two clauses swapped.
```

The failing order is exactly what the documented "most selective clause first"
performance tip recommends — so following our own guidance produced silently
wrong (empty) results. Wrong-empty is worse than slow.

## Root cause

In the fork's query-planner **direct executor**, the `emit-tuple` macro
(`reference-code/datahike/src/datahike/query/execute.cljc:501-508` pre-fix)
ignored `collect-datom-field` whenever the probe value came from an
entity-group MERGE clause (`collect-merge-idx >= 0`) — it always collected the
merge datom's **`.-v`**. For the failing shape the probe var `?tx` sits in the
merge clause's **tx slot** (field 3), so the producer's probe-set collected
`"Hello"` (the title value) instead of the tx id; the consumer scan
`[?tx :db/txInstant ?at]` probed tx entities against `#{"Hello"}` → `#{}`.

Instrumented JVM evidence (planner on, pre-fix):

```
group-direct scan= [?e :src/id src-1]        collect-field= 3 merge-idx= 0 collected= [Hello]
group-direct scan= [?tx :db/txInstant ?at]   probe-set= [Hello]  → #{}
```

Post-fix: `collected= [536870914]` → correct row.

Why only the pod saw it: `datahike.query/*force-legacy*` defaults **true on
the JVM** (planner opt-in via `DATAHIKE_QUERY_PLANNER=true`) but **false in
CLJS** — the pod always runs the planner. Only the collect-only probe-set
path was affected; when the producer supplies a find-var the consumer lacks
(e.g. `:find ?e ?tx`), the executor takes the probe-map path, which projects
via `find-source` and was already correct — which is why the repro's
`:find ?at ?tx` shape is load-bearing.

## Fix

Fork branch `fix/planner-collect-merge-datom-field` (on top of
`sync-upstream` @ `e6d196d5`):

- `22153e6f` — `emit-tuple` honors `collect-datom-field` on merge datoms
  (mirrors the scan-datom case), + regression test
  `datahike.test.query-planner-test/test-probe-join-on-merge-clause-tx-var`
  pinning the exact failing clause order and find shape (verified to fail
  pre-fix, pass post-fix).
- `da257d38` — CHANGELOG entry.

## Coordinate audit (who resolves the fork)

- **wire-server**: `clojure -M:simd:fork-deps:writer` → `:fork-deps` pins
  `seantempesta/datahike :git/sha e6d196d5` = submodule HEAD. Correct.
  BUT the **live process (2026-07-02) was started before the last bump and
  runs `7ef2b5de`** (one commit behind — CLJS-only diff, JVM unaffected);
  next restart picks up the pinned sha.
- **pod**: shadow-cljs deps-mode `:cljs` alias `:override-deps` pins the same
  fork sha `e6d196d5`, overriding the upstream mvn `0.8.1681` in
  `:extra-deps`. Correct.
- `~/src/datahike` is an older working checkout of the same fork (branch
  `fix/multi-group-direct-join`, a different planner bug whose resolution is
  already in `sync-upstream`); nothing resolves it. It still contains this
  bug unfixed and has stale staged WIP — candidate for cleanup.

## SHA-bump procedure (orchestrator)

1. Merge `fix/planner-collect-merge-datom-field` into the fork's
   `sync-upstream` (or rebase-push) and push to
   `github.com/seantempesta/datahike`; note the new sha.
2. In seon `deps.edn`, replace the old sha with the new sha in BOTH
   places: `:fork-deps` and `:cljs :override-deps`. (The
   `:replica-probe-jvm`/`:replica-peer-jvm` aliases that used to make this
   four places were deleted with the dormant replica-peer harness,
   2026-07-02.)
3. Bump the `reference-code/datahike` submodule pointer to the same sha
   (deps.edn:85 — src-secondary must stay byte-aligned).
4. `bin/seon restart wire-server` (also cures the stale-7ef2b5de process),
   rebuild + restart the pod (`bin/seon restart cljs-watch` then pod, or
   `bin/seon cluster reset` if a fresh world is wanted).

## Status

RESOLVED 2026-07-02. Fix merged to the fork's `sync-upstream` (= `da257d38`)
and pushed to GitHub; deps.edn bumped in all four aliases + submodule pointer
(seon commit `41c1b9b2`); wire-server + pod rebuilt on the new sha (this also
cured the stale `7ef2b5de` wire-server process); LIVE-PROVEN on the pod: the
previously-failing clause order returns the correct row (verified with both
`:db/txInstant` and `:seon.db/agent-id` tx joins). Skills caveats removed
(`556fa779`). Follow-up: [[datahike-planner-on-preexisting-failures]].
