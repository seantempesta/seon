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

R1/R2/R4/R8 APPROVED as one "clean signals" unit (fail-loud stays for
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
| R5 | Child footprint: mimalloc heap mislabeled as GPU; dev artifact corpus suspected (~160 MB vs 6 MB bun floor) | Bisect + compact release-style child artifact; scale-critical, likely simple |
| R6 | Load-truth probe (N-agent memory/latency/throughput) + child-containment PRD | New bounded unit after R5 |
| R7 | Triage NEW GAP units: frozen-turn-input purity; callable-projection correctness; persisted-program-error repair door | Three new PRD chunks; frozen-turn-input first (byte-identity law upstream) |
| R8 | 13 live `:core` faults + 621 instrumentation gaps (warnings lane observation) | Triage unit; overlaps R1/R2 causes |
| R9 | Namespaces block ~28k tokens for a fresh agent | MEASURED 2026-07-20 ([[research/namespaces-block-budget-2026-07-20]]): 28,145 tokens, ALL already compact — the configured 16-ns toolbelt × compact cards; `; schema` lines are 54%. Owner choice: accept, thin card schema density (~13k), or trim toolbelt. No default changed |
| R10 | konserve pin drift (deps.edn SHA not ancestor of submodule) | Re-pin or resync submodule — genuinely simple, needs direction choice |
| R11 | UDS reflection warnings (21, writer hot loop) | Type-hint pass — genuinely simple |
| R12 | 2 kondo errors + tool config (lint-as, tag list as computed rule) | Genuinely simple |
| R13 | `locks/` second creation path (reappeared post-hardening) | CLOSED 2026-07-20: no second path. The "reappearance" was a stale pre-fix git-status snapshot (snapshot HEAD 4f38818f = 07-19 20:41, before the 10:13 hardening 3d4aee61, which itself deleted the stray dir). `rg` shows one creator (`state.clj` `with-lock`, now guarded); no live process loads pre-fix operator code; full 289-test operator suite post-fix leaves no repo-root `locks/` |
| R14 | Watcher drift-vs-failure status | Issue filed; operator unit |
| R15 | Garbage: 681 MB unreferenced blobs, 1160 stale tmp files, heapsnapshot | DONE 2026-07-20: swept (blobs 681 MB→124 KB after live re-verification found 2 referenced hashes, not 0; heapsnapshot 268 MB; 1122 stale tmp probe files ≈2.2 GB). Design question below: [[#blob-gc-design-question-r15]] |
| R16 | Raise warnings token cap above 1024 for pathological bursts? | Keep 1024 (urgent-first survives); revisit with data |

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
