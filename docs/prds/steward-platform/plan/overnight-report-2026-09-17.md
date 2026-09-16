---
type: report
status: current (written 2026-09-17 01:20Z; the working edge in unsettled.md has the minute-by-minute record)
created: 2026-09-17
tags: [report, steward, overnight]
---

# Overnight report — 2026-09-16 → 09-17

Focus given: code indexing, error/fault storage and linking, robust test
infrastructure with per-test state on the reach digest, efficient updates by
identity. Everything below landed on `steward-platform`, gated cold by the
peer session (batches 30–57; ledger
[gate-ledger-2026-09-15](../../context-generation/research/gate-ledger-2026-09-15.md)).

## Broken first

0. **The checkout's store was deleted and re-created from genesis (10:17Z).**
   Actual cause (peer, `ccccea806`): `seon.cluster/operator-root` answered
   the JVM property `-Dseon.operator.root` — default's own root, the
   checkout — before the root the caller held, so an IN-PROCESS fixture run
   inside default's JVM (a lane following the repl rule, not a gate) built a
   "published root" under `tmp/` that resolved to `data/store` and deleted +
   cloned over it. A §2.1 fetch-at-call-time defect with a destructive
   consequence; every lane on default could have done it. Fixed: recursive
   deletion is admitted only under a root the JVM was declared to operate,
   the caller's root wins, `create-store!` refuses to delete a rostered
   store, every delete logs root/targets/caller/pid; regressions green.
   Recovered by a fourth refork (the day's recorded test results on default
   are lost; data is disposable by ruling). Gates resumed at batch 65 with
   the store size checked around each run. Also landed: the platform tier
   carries no destructive drill — the runner refuses a platform set in which
   any test reaches one of the three declared destructive owners (checked
   before the first platform task; three tests moved to the bulk tier), and
   `seon.test/run`/`check` refuse such a test in process under a development
   root with a typed refusal naming the owner (the seam that actually wiped
   the store). Both halves await cold proof in batches 66–67. Issue:
   `a-platform-tier-test-wiped-the-checkouts-store`.

1. **The store grows without collection.** `data/store` went 107 MB → 12 GB in
   eight hours with no periodic writer. The peer's measurement
   ([write-latency-vs-store-size](../../context-generation/research/write-latency-vs-store-size-2026-09-17.md))
   REFUTED the latency premise: an empty-delta commit costs 23–38 ms on the
   12 GB store, same as clean — Datahike's commit is O(delta). The 4–9 s
   samples were single commits flushing a large accumulated dirty-leaf set
   (one wrote 473 files / 39 MB: ~300 KB leaves × 6 indexes × ~10 ms fsync
   each). So GROWTH is the defect (nothing collects unreachable keys; a real
   `collect!` reclaimed 12.0 → 10.4 GB and counting), and in-process test
   runs are slow because their transactions dirty many leaves. Reset three
   times today. Three owner options are in that page, each keyed on an
   observable signal: footprint order-of-magnitude / key ceiling →
   `collect!`; a per-commit bound that names the batch and requests
   reclamation; narrower leaves or the vendored LMDB backend for the dev root.
   Measured after the third reset: 99 MB → 3.6 GB in 2.5 h (~1.4 GB/h) with
   two to four lanes and one gate batch active; the debug page stayed at
   0.42 s cold / 25 ms warm throughout.
2. **Live agents cannot yet close a code issue.** The trials
   ([issue-context-trials](../research/issue-context-trials-2026-09-16.md))
   ran seven cheapest-DeepSeek sessions on the arglists issue; candidate F
   (namespace picture) wrote the real fix and died one step from green on the
   30 s shell bound while trying to adopt its own edit. Four platform
   blockers found by the agents are now fixed (SCI pull of function rows;
   `dir` elided to nothing; `start!` workers never armed / no wake; plan
   derivation refusing past ~400 items). The remaining one is a decision
   (below): agent-facing adoption.
3. **Recurring classes named and partly killed today:** fixtures ignoring a
   refused `transact!` — after the one write helper was swept across 588
   sites in 96 files, the cold gate showed **142 tests in 47 namespaces had
   been passing over refused writes** (a shared fixture seeding the same
   receipt twice dominates); they are being made honest namespace by
   namespace, two of them may be real defects (call preparation, boot
   recovery) and are under separate triage; a
   `delay`/`defonce` caching a throwable (3 sightings); an open map keyed only
   by universal attributes shadowing a family (3 sightings; checker issue
   filed); one lane's intermediate edit refusing adoption for every lane (2).

## Landed (each with a landing note under `research/`)

- **Indexing:** derived citation resolver + delta-only issue index
  (edit-hook path free of the 13 s index); `:seon.fn.file/root` fact;
  `seon.program/shapes` dissolved into declared row schemas; adoption
  identities derived; gate-set 6.7 s → 11 ms (peer); analyzer facets.
- **Errors/faults:** provider faults visible to the prompt (peer);
  `faults-form` no longer pulls on nil for new agents; typed filesystem
  refusals; `seon.error/recording` as the one writer of facts.
- **Test infrastructure:** `:seon.test/reach` closure refs + `changed-since-green`;
  `seon.test.failure` components with file+line links; recording total
  (typed unknown, tombstone minting, rebased across publications, staged as
  data — the "Method code too large" class); first recorded gate since
  batch 30 at batch 46; the evaluation-context loader fix (base poison);
  base construction retried outside caller bounds; `transacted!` fixture
  helper; monotonic `:db/index` adoption without a refork.
- **Issues/tasks:** `seon.issue/generate` with detectors D1 (unpaired entity
  maps, 32) and D2 (undocumented public functions, 2 src / 28 test) → 63
  generated issues on default, idempotent by identity; the generated
  opening names its detector; effect facts (`:seon.effect/eval`, capability
  fn, file/span/program on `my.edit` effects).
- **Agent loop:** an agent creation IS the arm wake; the issue assignment
  datum is the worker's first wake; plan derivation in Clojure; `dir` shows
  rows + a token-sized elision with a requery offset.

## Owner decisions (2–3 options each, recommendation first)

1. **Agent-facing adoption** (blocker 2): (a) a successful `my.edit` write
   requests in-process adoption of that path, bounded, reported as the
   effect result [recommended: the agent already made the change; adoption
   is its consequence]; (b) `my.test/check` adopts changed `src`
   namespaces the way it reloads test namespaces; (c) keep the shell path
   and raise the shell bound to the measured 60–90 s adoption.
2. **Which opening to keep:** F (namespace picture) + B's two exact
   completing calls [recommended]; keep A as the floor; drop C, D, E, G.
3. **Store growth:** await the peer's three options; the decision is the
   signal the reclamation keys on.
4. **Generated issues and tests:** the detector decides "done" for a
   generated issue (no fabricated test row) [recommended, landed]; `start!`
   therefore needs a detector-driven completion path (issue filed).
5. **D2 scope:** generate docstring issues for test helpers (28) or src
   only (2)? [recommended: src by default; test on request].
6. Earlier parked: R1 identity strings→symbols; call-arities tuple vs
   interned family; `seon.commit` entity; retention removal; cold page slice 2.

## Still open

`render-coverage` root address (peer lane), turn settlement cost (peer astra),
gate-set output contract (peer), write-floor research (peer), the seven
neighbouring reds the plan lane saw in process (cold verdict pending), the
fixture-refusal detector, the open-map checker.
