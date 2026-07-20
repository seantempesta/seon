---
type: prd
status: active
tags: [prd, architecture, database, agent, web]
---

# Master accounting register

One row per known issue across all detection sources (2026-07-20:
six audits, adversarial review, three sweeps, steering audit, issues
triage, mechanical detectors, live detectors, scale profiling). Rows
reference their evidence doc; this register is the decision surface —
nothing here is fixed without an owner ruling or a claiming stage.

## Closed today (proof in ledger/commits)

B1-B7, B10, B12, B13 (see [[roadmap]] ledger); skills drift x2; my.data
envelope swallow; writer TERM-race publish loss; release --help; stray
locks (first path); file-block proven + example; warnings block live,
15/15 checks verified current.

## Scheduled — claimed by a stage, awaiting execution order

| Item | Stage | Evidence |
|---|---|---|
| Adversarial strengthened fixes (20, folded) | per PRD | [[research/adversarial-review-findings-2026-07-20]] |
| Steering gaps G1-G4 + proposed G8-G11 extensions | 1.6 | [[research/corrective-steering-audit-2026-07-20]], triage §gaps |
| Pod→client/cluster rename (freeze protocol, persisted forms) | 2 | pod plan |
| Logging format unification, timbre grounding | 3 | [[logging-unification]] |
| Config through aero, per-op ALS (probe-validated), grants seam | 4 | [[config-through-aero]] |
| Route-authority collapse + /sse + crossing runbook | 4 | [[research/route-authority-collapse-2026-07-20]] |
| Reactive fold-ins: client advertisement, serve poll, close! at shutdown | 4-5 | [[research/bespoke-reactive-sweep-2026-07-20]] |
| Envelope-key + ok?-discriminator convergence (**ruling flipped: explicit ok? key** — triage counterevidence `installed-schema-map-misclassified`; lane B's live bug confirmed the class) | 5 | [[research/envelope-symbol-conformance-2026-07-20]] |
| Unresolved-symbol single semantics; render nil-vanish | 5 | same |
| Deletions: ctx.usage wiring (Muse shape first), docstring-predicate .cljc, canvas test B9, embed retry collapse, marker-token mirror, rows->projection dedup | 5 | [[deletions-and-wiring]], roadmap stage 5 |
| Data-browser implementation (measured, corrected) | 1.5 | [[data-browser]] |
| Triage FOLD rows (10: [:maybe] regs inside G2, transact-response union, home-requires merge, ALS tx-meta unify, parse-forms keys, debug-feed threading, turn-debug ref projection, 2 test fixes) | named stages | [[research/issues-triage-2026-07-20]] |

## Rulings 2026-07-20 (third round)

R1/R2/R4/R8 APPROVED as one "clean signals" unit. STATUS: R1 CLOSED
`6c9bfe83` (root cause: Bun drops ALS in unhandledRejection; handler now
on the form's own Promise; live-proven :agent datom 4287, pod survives);
R4 CLOSED `1846a3ff` (log sink was repo-relative — test runs appended
into the live log; per-process explicit claim). R2 (program-row
rejection rule) and R8 residue remain open for a follow-on unit. (fail-loud stays for
genuine core faults; dev/MCP funnels become agent-scope; fixture noise
leaves the production error channel; the 13 faults + 621 gaps triaged).
R5 bisect NOW. R7: frozen-turn-inputs PRD carved first. R9/R11/R12/R13/
R14/R15 all GO. R10 investigate-first. R16 keep the 1024 cap.

## Needs an owner ruling (new since last rulings)

| # | Item | Recommendation |
|---|---|---|
| R1 | Dev/MCP fault scope: a REPL typo can crash the pod (proven live); scoping is path-dependent | Classify all dev/MCP funnels `:agent`-scope; acceptance in `dev-eval-fault-scope-misses-mcp-funnels` — small, high-value, candidate for "genuinely simple" fix |
| R2 | Dev/MCP eval history silently unrecorded (27x program-row rejections) | Diagnose-first unit; likely one rejection rule; pairs with R1 |
| R3 | Fault forensics: no branch head on fault datoms; frames are constructor noise | One observability unit; blocks the fault→`cluster fork` chain |
| R4 | `:seon.ai/complete` 61% error-channel flood, 7:1 fixture noise | Route fixture noise out of production error path; small |
| R5 | Child footprint | **BISECTED** ([[research/child-footprint-bisect-2026-07-20]]): dev-artifact hypothesis FALSIFIED (release bundle loads larger); composition = ~90 MB program load + 91 MB session/admission projection + 34 MB prompt render; **peak-shaped retention** — one heavy turn inflates 220→416 MB permanently (JSC capacity + mimalloc dirty never released; `BUN_JSC_forceRAMSize` cut load ~15%). NOT simple; three bounded units sized in the doc (require-closure trim, leaner child admission, JSC heap cap). N=100 ≈ 18-22 GB steady |
| R6 | Load-truth probe + child containment | R5 bisect supplies the memory model; the containment PRD should lead with the JSC heap-cap lever (burst retention is the dominant term — same pattern as the MLX cache-limit law) |
| R7 | Triage NEW GAP units: frozen-turn-input purity; callable-projection correctness; persisted-program-error repair door | Three new PRD chunks; frozen-turn-input first (byte-identity law upstream) |
| R8 | 13 live `:core` faults + 621 instrumentation gaps (warnings lane observation) | Triage unit; overlaps R1/R2 causes |
| R9 | Namespaces block ~28k tokens for a fresh agent | MEASURED 2026-07-20 ([[research/namespaces-block-budget-2026-07-20]]): 28,145 tokens, ALL already compact — the configured 16-ns toolbelt × compact cards; `; schema` lines are 54%. Owner choice: accept, thin card schema density (~13k), or trim toolbelt. No default changed |
| R10 | konserve pin drift (deps.edn SHA not ancestor of submodule) | DONE 2026-07-20 (58de6093): the deps.edn pin was the deliberate truth — c5c76da9 advanced it to the `seon-0.9.359-legacy-header` tip (b5c99bc0, ports legacy-header compat + delete-store fixes) while the gitlink stayed on the superseded `sync-only` 0.9.356 branch. Submodule advanced to the pin; deps.edn unchanged; bin/test-writer green; issue archived |
| R11 | UDS reflection warnings (21, writer hot loop) | DONE 2026-07-20 (b1a69b7f): plain hints were impossible — bb/SCI loads the ns and fails analysis on non-allowlisted Selector/SelectionKey hints. uds.clj → uds.cljc with `#?(:bb reflective :clj hinted)` interop wrappers (db/branch/id/protocol precedent); 21→0 warnings; bin/test-writer 231/1891 green; operator loads; bin/lint gains the computed shadowed-.cljc CLJ-only pass; issue archived |
| R12 | 2 kondo errors + tool config (lint-as, tag list as computed rule) | DONE 2026-07-20 (4c56994b): config.cljs require added, eval.cljs duplicate require removed, `with-authority` :lint-as def-catch-all; markdown tag/type vocabulary now corpus-derived (belongs once ≥2 vault docs carry it), 96→12 findings all genuine singletons; remaining kondo errors are instrument.cljc:14 (report item 11, not this unit) + error_record_test.cljs (concurrent-lane churn); issues archived |
| R13 | `locks/` second creation path (reappeared post-hardening) | CLOSED 2026-07-20: no second path. The "reappearance" was a stale pre-fix git-status snapshot (snapshot HEAD 4f38818f = 07-19 20:41, before the 10:13 hardening 3d4aee61, which itself deleted the stray dir). `rg` shows one creator (`state.clj` `with-lock`, now guarded); no live process loads pre-fix operator code; full 289-test operator suite post-fix leaves no repo-root `locks/` |
| R14 | Watcher drift-vs-failure status | DONE 2026-07-20 (3a18c4cd): `:seon.dev.target.status/rebuilding` + per-process `rebuild-pending?`; failed build still degrades; 289 operator tests green; issue archived |
| R15 | Garbage: 681 MB unreferenced blobs, 1160 stale tmp files, heapsnapshot | DONE 2026-07-20: swept (blobs 681 MB→124 KB after live re-verification found 2 referenced hashes, not 0; heapsnapshot 268 MB; 1122 stale tmp probe files ≈2.2 GB). Design question below: [[#blob-gc-design-question-r15]] |
| R16 | Raise warnings token cap above 1024 for pathological bursts? | Keep 1024 (urgent-first survives); revisit with data |

## Shared-runtime verdict (2026-07-20)

[[research/bun-shared-memory-options-2026-07-20]]: live JS heap graphs
CANNOT be shared between VMs, Workers, or processes (every mechanism
grounded in vendored Bun source; fork-after-warm definitively impossible;
Workers strictly worse for containment). The 91 MB "projection" band is
not shipped data (wire caps at 6 MB) — it is `schema/build-projection`
EAGERLY compiling every Malli schema (`schema.cljc:307-315`). Efficient
"sharing" therefore means not duplicating: (1) OWNER-CORRECTED: not turn-scoped swapping — a child is the agent's
stable live runtime (defs, result vars, in-flight work) and active
agents keep theirs; the lever is PARKING idle agents (child reaped;
agents are restart-safe database data) with 1-2 warm spares — memory
proportional to ACTIVE agents; (2) burst-retention
fix + `BUN_JSC_forceRAMSize` backstop; (3) lazy validator compilation at
admission (kills most of the 91 MB Seon-side); (4) require-closure
shrink. Deferred as non-major: bytecode (latency only), mmap (no byte
tier big enough), Workers (negative). Cross-agent block sharing: measure
render cost first; prefix byte-identity discipline suffices for the
provider prompt-cache win without a new mechanism.

## Sci execution-runtime verdict (2026-07-20, measured)

[[research/sci-execution-child-feasibility-2026-07-20]]: sci-JIT in Bun —
burst retention RETURNS (89-91 MB settled after a 1.5 GB peak; self-host
stays at 416 MB permanently); small-form eval latency 10-16x FASTER than
self-host; all four semantic gaps PASS live (native ^:async/await over
real Promises, agent defmacro works, malli wrapper yields the standard
envelope, def/redef/in-ns work — better than self-host for value defs);
sci :interrupt-fn cancels runaway CPU in-process (impossible today). JVM
sci host (owner extension): 22.7 KB marginal per context at N=100 (true
structural sharing), Thread/interrupt containment 0 ms, port split of the
137 my.* fns = 42% pure / 46% db-boundary (become SIMPLER synchronous
calls) / 12% genuinely js-bound; the UDS transport already has a JVM
client side. bb-runs-packaged-CLJS: definitively impossible
(demonstrated). Honest blockers in the doc: sci≠cljs.js general
semantics, the 91 MB eager-schema band untouched (lazy validators fix it
orthogonally), ~60 MB bundle-proportional floor, retention unproven at
production anchoring. Architecture decision pending owner.

## Independent backlog (87 notes, themed)

See [[research/issues-triage-2026-07-20]] §INDEPENDENT: inspect-ai
harness, restore/branch lifecycle, datahike fork internals, downstream/
acme, diffusion. Not this program's scope; 7 STALE notes ready to close.

## Blob GC design question (R15)

When may an unreferenced blob be collected, given that database values
support `as-of`, `since`, and `history` reads? A blob file is referenced
by a `:my.blob/hash` identity datom; turn captures reach it through
`:seon.agent.turn/prompt-blob` / `reply-blob` refs (and
`:seon.runtime.recovery/diagnostic-blob`). Datahike never forgets a
datom, so a hash retracted from the current value is still reachable
from any historical database value — deleting its file silently breaks
`as-of` reproduction (turn forensics, frozen-turn-input replays).

The candidate rules, weakest to strongest:

1. **Current-value reachability** — collect when no current datom holds
   the hash. Unsafe: breaks every historical read.
2. **History reachability** (used for today's manual sweep) — collect
   only hashes absent from `(db/history (db/db))` of every database and
   live branch on the cluster's store. Safe for the store as it exists,
   but a restore/`cluster fork` of an older commit could resurrect a
   branch whose history references a collected hash.
3. **Commit-graph reachability** — collect only hashes unreachable from
   any retained commit in the store's branch/commit graph (the same
   boundary Datahike GC uses for konserve nodes). Equivalent to tying
   blob GC to database GC: a blob may be collected exactly when every
   commit that could reference it has itself been collected.

Rule 3 is the principled answer: blob lifetime = commit lifetime, one
retention dial for both. Today there is no automated collection at all
(the 681 MB was residue from cluster resets — new store, orphaned blob
dir), so the cheap first mechanism is: on `cluster reset`, delete the
cluster's blob dir with the store it replaces. Full rule-3 GC belongs
with a Datahike-GC/retention unit, not a bespoke scanner.

## System truth (evidence, not defects)

Read path: acquire 0.24 ms; query p50 1.9 ms; deep pull 2.5 ms; 50
concurrent ≈ 0.67 ms each. Schema drift zero (360/360); program graph
zero anomalies; zero dangling refs/stored nils. Child JS workload
~15-20 MB (bun floor 5.9 MB). Three suites green at every checkpoint.
