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

00. **A write storm filled the store at a gigabyte a minute (12:20–12:40Z).**
   An in-process test run inside default's JVM registered a synthetic schema
   key into the shared registry and never restored it; every turn write then
   refused (`invalid-schema` inside the turn loop's `:db.fn/call`) and the
   turn proc re-fired without bound, each failed attempt flushing dirty
   index leaves: 2.5 → 21 GB with near-zero commits. Fifth reset. Two
   classes, fix lane running: a repeated write refusal must park the agent
   with ONE fault (bounded execution), and in-process tests must leave the
   schema registry byte-identical (own nothing global). Issue:
   `a-failing-turn-write-refires-without-bound-and-fills-the-store`. This
   also explains part of the day's "store growth": the earlier 1.4 GB/h was
   ordinary churn; today's spike was this storm. Class 2 landed
   (`f86ec57ed`: a refused turn write is bounded by a declared dial and
   becomes one fault); class 1 (registry preservation) is in flight.
   Landing that dial exposed one more class: a REQUIRED config dial whose
   decision is not yet in the effective config makes `mcp-io-prepl` refuse
   every connection at connect time — MCP, `config apply` (the repair
   itself), gate recording and every in-process run all read as
   "Connection reset" for ~45 minutes until a restart reconciled it. Issue:
   `a-missing-required-dial-kills-every-io-prepl-connection` (blocker: the
   connection seam must serve a typed refusal in the value, never refuse the
   socket; a new required dial must ship with its decision in the same
   publication). Both classes landed: a refused turn write is bounded by a
   declared dial and becomes one fault (`f86ec57ed`, `f0cb3f692`), and the
   storm's real root turned out to be CUSTODY, not a registry cache — an
   in-process test body inherited the agent evaluation's connection through
   `bound-fn`, so elided-arity fixture writes landed on the live cluster;
   every test Var now runs without custody, the drift detector snapshots
   schema keys, and `seon.test/run` restores the projection (`f3b61b975`).
   One more finding from it, queued: a changed contract stayed armed with
   its PREVIOUS shape across three converged adoptions
   (`adoption-can-leave-a-changed-contract-armed-with-its-previous-shape`).

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
   the store). Both halves proven cold in batch 66 (platform green with the checker running first; the in-process refusal regressions green; store intact across the run). Issue:
   `a-platform-tier-test-wiped-the-checkouts-store`.

0b. **The effect door refuses every declared capability** (since the
   effect-facts change `0e15593aa`): `seon.effect/accepts-request?`
   validates a one-argument request against owners that take
   `[request effective]`, so `my.fs`/`my.web` requests through the door
   answer false; it surfaced only when a fixture stopped hiding its refused
   seed. Fix lane running (the door asks the owner's contract about the
   request position). Issue:
   `the-effect-door-validates-a-one-argument-request-against-a-two-argument-owner`.
   A second real defect from the same pass: the opening walk's namespace
   candidate hands a bare symbol where the contract wants an entity lookup
   (`91f536c36`), fix lane running.

0c. **The cluster's live projection drops committed storable declarations.**
   When an agent admits a schema through a turn (Juniper's own scenario does
   this on every reseed), the identity attribute and entity map are
   committed as facts but dropped from the in-memory projection at install;
   they re-derive later and read as drift. A stale mirror of the declaration
   facts — the write storm's disease one layer up. Fix lane running at the
   projection-advance seam. Issue:
   `a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection`.

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
   0.42 s cold / 25 ms warm throughout. The single largest writer was then
   measured and fixed: each cold gate's result recording retracted and
   re-asserted every test's `:seon.test/reach` set (157,981 datoms for an
   unchanged 93-result completion, 94.6% of it reach; index-node rewrite
   amplification made that 5–18 MB of store per recorded test). Recording
   now emits only the delta — 0 datoms for an unchanged re-record
   (`f272b9e6e`; proven cold in batch 79: a second platform run on the same HEAD left the store at 1.1 GB). Six resets
   today in total; the reclamation-signal decision still stands for the
   ordinary churn.
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
   namespace; of the two suspected real defects, one WAS real — the
   instrumentation refusal carried a leaf value instead of the checked
   value and arity refusals lost their number (fixed at `seon.instrument`) —
   and the other was expectation drift in boot recovery (fixed at the test); a
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
  helper; monotonic `:db/index` adoption without a refork; the cold-arming
  class closed (one derivation for a projection's bound predicates at
  fourteen read sites, with a program-graph checker); `:seon.test/long` a
  program fact with `check` excluding long tests by name; recording made
  total on every absent identity; the platform tier and in-process runs both
  refusing destructive drills.
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
6. **Write admission of partial upserts:** a map keyed by an identity is
   validated against the WHOLE entity schema, so updating one attribute of
   an existing entity is refused for keys it already carries (three
   sightings today once fixtures stopped hiding refusals). Options in
   `a-partial-upsert-of-an-existing-entity-is-validated-against-its-complete-required-keys`
   [recommended: validate against the required keys as they stand after the
   transaction, merging the existing entity inside the transaction function].
7. **Which budget owns the prompt?** The agent's history is rendered as ONE
   string through the value renderer and cut at a character offset by the
   VALUE profile (mid-form, no turn named) — 199 concurrency assertions
   could never see their payload, and `seon.render.transcript-run-test`
   (11) sees the HTML turns while the AI render answers an empty string. Options: (a) the prompt's own
   `:seon.config.ai/prompt-token-budget` cuts whole evaluations oldest
   first and the value profile bounds each shown result [recommended]; (b)
   raise the value budget for the history path; (c) keep as is and relax
   the tests (hides the defect). Issue:
   `the-agents-history-is-cut-as-one-string-by-the-value-budget`.
8. Earlier parked: R1 identity strings→symbols; call-arities tuple vs
   interned family; `seon.commit` entity; retention removal; cold page slice 2.

## Still open

`render-coverage` root address (peer lane), turn settlement cost (peer astra),
gate-set output contract (peer), write-floor research (peer), the seven
neighbouring reds the plan lane saw in process (cold verdict pending), the
fixture-refusal detector, the open-map checker.
