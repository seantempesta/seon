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
| R9 | Namespaces block ~28k tokens for a fresh agent | Context-budget investigation (compact selection exists — verify config) |
| R10 | konserve pin drift (deps.edn SHA not ancestor of submodule) | Re-pin or resync submodule — genuinely simple, needs direction choice |
| R11 | UDS reflection warnings (21, writer hot loop) | Type-hint pass — genuinely simple |
| R12 | 2 kondo errors + tool config (lint-as, tag list as computed rule) | Genuinely simple |
| R13 | `locks/` second creation path (reappeared post-hardening) | Trace via live detectors' residue data |
| R14 | Watcher drift-vs-failure status | Issue filed; operator unit |
| R15 | Garbage: 681 MB unreferenced blobs, 1160 stale tmp files, heapsnapshot | One hygiene sweep + blob GC design question |
| R16 | Raise warnings token cap above 1024 for pathological bursts? | Keep 1024 (urgent-first survives); revisit with data |

## Independent backlog (87 notes, themed)

See [[research/issues-triage-2026-07-20]] §INDEPENDENT: inspect-ai
harness, restore/branch lifecycle, datahike fork internals, downstream/
acme, diffusion. Not this program's scope; 7 STALE notes ready to close.

## System truth (evidence, not defects)

Read path: acquire 0.24 ms; query p50 1.9 ms; deep pull 2.5 ms; 50
concurrent ≈ 0.67 ms each. Schema drift zero (360/360); program graph
zero anomalies; zero dangling refs/stored nils. Child JS workload
~15-20 MB (bun floor 5.9 MB). Three suites green at every checkpoint.
