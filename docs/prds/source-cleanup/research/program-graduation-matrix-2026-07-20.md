---
type: research
status: active
tags: [research, prd, architecture, testing]
---

# Source-cleanup program graduation matrix (2026-07-20)

## Purpose and evidence rule

This matrix projects the complete graduation contract from [[../roadmap]],
[[../register]], and the issue triage into one ordered proof checklist. It does
not replace those ledgers and does not claim the program is complete.

A commit is implementation evidence, a focused test is local behavioral
evidence, and a live observation is runtime evidence. None substitutes for a
broader gate. In particular, the clean CLJS checkpoint at `286180f7` and writer
checkpoint at `10b69365` predate later tracked edits and therefore cannot count
as either of the final two frozen full-suite passes.

The audit began at HEAD `e7cc6f94` and reconciled the concurrent numeric-cap
ruling `38f24f39` before commit. The shared checkout had
tracked source and test edits at audit time, including the active U4
`turn.cljs`/`ai.cljs` lane and database-host work. No live or full-suite result
from that moving tree is graduation evidence.

## Scope classification

The source-cleanup program owns:

- every roadmap stage and Stage 1.5/1.6 boundary;
- the issue-triage `COVERED` and `FOLD` rows assigned to those stages;
- G1-G4 and the ruled G8-G11 corrective-steering extensions;
- every adversarial-review correction folded into the owning PRD; and
- the final twice-consecutive three-suite and frozen live-cluster gates.

The issue-triage `INDEPENDENT` rows are not silently required for this
program. They need an explicit successor PRD or owner disposition before
graduation, not opportunistic implementation here. The larger execution-host
U-series, child-memory work, branch lifecycle, Inspect, Datahike internals,
downstream packaging, and provider work are successor-program concerns except
where their concurrent ownership blocks a source freeze.

## Ordered implementation and proof matrix

The letter IDs below are a compact A-H projection of the existing dependency
spine; they introduce no new units or reordering.

| ID | Authorized boundary | Current authoritative evidence | Missing or weak evidence that prevents graduation | Exit proof |
|---|---|---|---|---|
| A | Stage 1.6 corrective steering and frozen overlap release | G1/G2 source `0b991436`; G8 `94e38e15` + `ee6dde8c`; G6 `cd7ffdf0`; G3 `8e008470`; G9 `f84a9efc`; G10 record half `418a3844`; G11 source `9778fa86`; prior full CLJS `286180f7` | Fresh-start G1/G2 rejection and corrected-call proof; G3/G9 live narration; G10 behavioral shape probe; G11 real-browser pending/success/failure/corrected retry/rapid duplicate submit; usage/debug live surfaces. Prior full CLJS is stale for this boundary. | One frozen source revision, focused proofs plus full CLJS, live agent probes, G11 browser lifecycle, server-side feed where applicable, and covered issue notes closed with that evidence. |
| B | Stage 1.5 Unit 1A activated schema projection | Design boundary `c952c793`; bounded prerequisite Unit 0 implemented by `d42a88de` | Unit 1A is design-only: no implementation commit or focused ambiguity/open-map/elision/cache-identity proof. | `schema.cljc`/`schema_test.cljs` implementation, deterministic all-match APIs over one activated projection, and focused gate at a frozen contract. |
| C | Stage 1.5 Unit 1B plus original-key and total-work contracts | Unit 1B design `817a821f`; key design `ddf2b5c2`; budget design `3d5943db`; numeric defaults ruled by `38f24f39`; open issues [[../../../seon/issues/projected-map-keys-are-not-drill-paths]] and [[../../../seon/issues/value-drill-has-no-total-work-bounds]] | All implementation is still absent. The exact 32-segment, 4096-byte, and 1024-realized-item policy is settled, but no parent/child rejection-before-work instrumentation exists. | Single-sample schema-aware projection; original keys separately addressable; path-segment, encoded-byte, and total-realization caps enforced before work; visited-item tests; both issues closed. |
| D | Stage 1.5 execution-child sampling transport | Closed-frame/lifecycle design `a568deef` | No protocol implementation, correlated frame tests, retirement race proof, or child-side independent bound enforcement. | Closed ordinary-data request/result/error frames; current-generation ownership; no parent lookup or retired-child retry; focused protocol and kill/retirement proofs. |
| E | Stage 1.5 value route, universal UI/custom dispatch, and integration | Route/auth design `7b6e2243`; UI migration design `e7cc6f94` | No route implementation, authorization send-count proof, `/data` migration, eval-card drill, custom property dispatch, plan-tree deletion, child-retirement server proof, SSE proof, or real-browser proof. | Complete integrated acceptance matrix in `universal-data-browser-ui-migration-boundary-2026-07-20.md` against one revision; full CLJS; open query-shape and browser issues closed. |
| F | Stage 2 atomic pod-term retirement | Persisted-form plan and readiness audit exist | Explicitly not freeze-ready: overlapping owners, retained branch/process records, worktree dispositions, stale artifacts, and recomputed terminology inventory remain. No atomic rename or post-cutover proof exists. | Freeze all lanes; quiesce default and ACME under pre-rename code; atomic code/downstream/docs/skills rename; regenerate skill adapters; three suites; cold `bin/seon up`; web UI; restarted MCP client round-trip; vendor-excluded terminology sweep. |
| G | Stage 3 logging completion, then Stage 4 route/config/reactive authority | Logging source `51f28046`; config readiness `69ce49f9`; route readiness `c5900e74`; Aero and ALS research | Stage 3 lacks paired frozen client/writer log lines, loop-fault tail, and safe log cleanup. Stage 4 route authority and reactive-router cuts are not implemented; per-operation pinned database-value + full-config ALS is not implemented; launch/config/env/default reconciliation and all live gates remain. | First close Stage 3 frozen live proofs. Then atomic route cut with duplicate-path rollback test, reactive cut, per-operation config spine, config idempotence, default and ACME boot, live policy update, and owning issues closed. |
| H | Stage 5 remaining deletions and small unifications | Many rows closed: retry `84ab7097`, marker tokens `6920227b`, usage `b819df26`, dead UI `4ac2902e`, B9 `ecb8b4b7`, namespace predicates `87d415e8`, H5 `904cf4ab`, H6 `6246181b`, H2 producer `431ce8a7`, parser `3a0dbd31` + `58fb020d`, run poll `6f157a3a`, runner lookup `8aeadd3d` | Stored rows-to-projection dedup remains; arbitrary-result explicit union and closed error discriminator remain; unresolved-symbol nil-vanish remains; client advertisement and shutdown `reactive/close!` remain after Stage 4; ALS tx-meta fold and several issue-triage rows remain open; usage/debug live proof remains. | All remaining Stage 5 owner groups implemented after their prerequisites, focused and three-suite proof, require-graph/orphan sweep, callable-index/deprecation sweep, and inherited issue notes closed. |

## Inherited issue-note matrix

An issue marked open remains ungraduated even when source appears to contain a
candidate fix. The close requires the note to carry the implementing commit and
the acceptance-level proof, then move to the archive when repository practice
requires it.

| Program boundary | Open inherited notes or unfinished acceptance |
|---|---|
| Stage 1.6 | [[../../../seon/issues/compiled-program-contains-nilable-value-schemas]], [[../../../seon/issues/transact-output-schema-crashed-child-on-ordinary-error]], [[../../../seon/issues/database-query-tuple-shape-legibility]], and [[../../../seon/issues/canvas-controls-hide-pending-and-failure]]. Source/test work may exist for parts; live and note-close evidence is still missing. |
| Stage 1.5 | [[../../../seon/issues/projected-map-keys-are-not-drill-paths]], [[../../../seon/issues/value-drill-has-no-total-work-bounds]], plus the renderer and behavioral half of [[../../../seon/issues/database-query-tuple-shape-legibility]]. |
| Stage 4 | [[../../../seon/issues/static-routes-bypass-database-route-authority]], [[../../../seon/issues/config-apply-rebuilds-unchanged-runtime]], and [[../../../seon/issues/root-context-replaces-base-capability-requires]]. |
| Stages 4-5 reactive | [[../../../seon/issues/bespoke-reactive-loops-outside-seon-reactive]]. The `/agents/run` poll is closed, but router attachment, client advertisement, shutdown close, and their live proof remain. |
| Stage 5 envelopes/rendering | [[../../../seon/issues/installed-schema-map-misclassified-as-database-error]], [[../../../seon/issues/turn-debug-treated-database-error-as-entity-id]], [[../../../seon/issues/turn-debug-must-project-rendered-transaction-ref]], and [[../../../seon/issues/render-entity-converters-silently-vanish-on-unresolved-symbol]]. |
| Stage 5 plumbing/conformance | [[../../../seon/issues/als-unify-tx-meta]], [[../../../seon/issues/parse-forms-entry-schema-and-bare-keys]], and [[../../../seon/issues/debug-feed-captures-foreign-database-reads]]. The debug-feed note says source work landed but explicitly retains the live observation gate. |

The triage's stale notes and already-fixed focused-test notes must be reconciled
against current files and archived with evidence; leaving them open would make
the register's “all ledger rows closed” claim false even if no code change is
needed.

## Successor-PRD and explicit-disposition matrix

These findings are real but do not enter the A-H implementation spine without
an owner ruling. Graduation requires a durable link to their owning successor,
or a written scope disposition in the register.

| Finding family | Required disposition |
|---|---|
| Persisted program repair door | Execution/database-authority successor; do not fold into the small persist/render steering unit. |
| Frozen turn inputs and context purity | `docs/prds/frozen-turn-inputs/`; its independent roadmap owns provider-retry and historical-byte purity. Source-cleanup records the dependency, not duplicate implementation. |
| Callable projection correctness | Bounded successor unit for `compact-fn-head`, `callable-contract`, and program indexing, unless explicitly authorized into Stage 5. |
| Context block cache-gradient ordering | Context architecture successor after volatility measurement. |
| Restore/branch lifecycle, execution-host U-series, child footprint, Datahike internals, Inspect, downstream, packaging, provider, and platform notes | Their existing owner PRDs/backlogs. They block a freeze only while they own overlapping files or live state. |
| B8/B11 original intermittents | They remain in the live bug ledger. The final consecutive full-suite requirement is their graduation detector; a recurrence reopens diagnosis and resets the consecutive count. |

## Final freeze and graduation sequence

The final evidence must be generated in this order so later source movement
cannot invalidate earlier proof.

1. Reconcile A-H and every inherited note. Record the exact intended commit.
2. Pause every source-editing lane and obtain explicit path handoffs. Resolve
   retained branches and build artifacts through their owners; do not delete
   another lane's state merely to obtain a clean status.
3. Require no tracked source/test/config/docs changes that affect the artifact
   digest. Record `git status`, HEAD, dependency pins, and built artifact
   identity. Unrelated reproducible caches may remain but cannot be evidence.
4. If Stage 2 has not yet executed, perform its pre-rename quiescence and
   atomic cut before the final builds. Never restart across the vocabulary
   boundary.
5. Build from the fixed commit and run, without source movement, the complete
   `bin/test-cljs`, `bin/test-writer`, and `bin/seon test operator` suites.
6. Run the same three complete suites a second consecutive time at the exact
   same commit. Any failure, warning forbidden by a gate, intermittent B8/B11
   recurrence, rebuild, or source edit resets the consecutive pair.
7. Cold-start the default cluster from that artifact and prove the global live
   graduation set: warn check, real interrupted-run recovery decision, MCP
   `await` after the required client restart, same-shape client/writer log
   lines, and loop fault visible through `seon.log/tail`.
8. In the same frozen session, execute the outstanding stage-specific live
   matrices: Stage 1.6 directive/narration/query/control lifecycle; Stage 1.5
   authorization, bounded child paging, retirement, server-side SSE, and real
   browser; Stage 2 terminology/MCP/web; Stage 3 log hygiene; Stage 4 config,
   route refresh, readiness, default/ACME; Stage 5 usage/debug surfaces.
9. Audit database and repository invariants: zero anomalies, dangling refs,
   stored nils, active deprecated callable functions, orphan regressions, or
   second owners detected by the named sweeps. Verify the child workload target
   only with the program's selected runtime architecture and measurement
   method; do not reuse an exploratory benchmark as production proof.
10. Attach command outputs, live identifiers, browser/server evidence, issue
    closures, and commit hashes to the roadmaps. Re-read the register
    requirement by requirement. Only then can the program be marked complete.

## Earliest unsettled contract

The dependency-critical exit is A: finish the frozen Stage 1.6 live/browser
gate after the current U4 owner releases the overlapping source and lifecycle.
The next implementation-ready boundary is B, Unit 1A. C through H must not
invent or bypass those contracts. The final graduation gate remains the exact
twice-consecutive three-suite pair plus the complete frozen live session above.
