---
type: review
status: complete
date: 2026-09-26
reviewer: gpt-6-astra
source-pin: 5bed888d02c300ca3dc798109687f2b88471a436
verdict: ACCEPT-AFTER-FIXES
blocking-findings: 0
---

# REPL parity and SCI fork ownership: independent diff review

The source changes address the causes at their owners. They preserve SCI's actual result, attach source location at the evaluating thread, and stamp newly created Vars with the fork's existing generation. I found no blocking source defect in these diffs. Acceptance still needs the missing installed proof and corrected evidence claims.

## Scope and evidence

Read `CLAUDE.md`, the owning issues `agent-repl-error-output-diverges-from-clojure-main-for-every-ordinary-mistake.md` and `letfn-and-defrecord-with-an-inline-protocol-fail-in-the-agent-repl.md`, and the complete landings `landing/repl-parity-2026-09-26.md` and `landing/fix-sci-fork-vars-2026-09-26.md`. These issues and cause/fix sections are the design authority for the small fixes; no separate first-pass design was identified.

Reviewed actual `5a81616a3`, `d9566ad05`, `git -C reference-code/sci show 49ce5cb9`, and the gitlink/Seon regression in `5bed888d0`. References are to that Seon pin and SCI `49ce5cb988288509ecc3f00fb8a9915cc9b98de2`. No JVM, tests, dependency reload or live parity probe was run by this reviewer. Reported historical results below belong to the implementation landing.

## Findings

### P1 — should-fix: the combined fix has no installed Seon proof in its landing

**Location:** `docs/research/agent-platform/landing/fix-sci-fork-vars-2026-09-26.md:14` and `:84`; `docs/research/agent-platform/landing/repl-parity-2026-09-26.md:89`.

**Failure scenario:** the checkout points at the corrected SCI fork while the running JVM still holds the old analyzer/Var constructors. Source review and the bare SCI regression can be green while the agent entrance still fails `letfn` or inline protocols. The parity landing also says its checks ran against HEAD without its diff. Neither result proves publication, installed dependency adoption, arming or the agent's response for the combined commits.

**Smallest fix:** root performs the dependency restart/adoption required by the existing operating procedure, verifies the loaded revision, then records the named focused Seon regressions and ordinary branch REPL probes against the combined change. Include elapsed/allocation evidence and retain known failures as failures. Do not rerun a provider or create another evaluator merely to get proof. This review's no-JVM instruction remains in force; it does not authorize the reviewer to supply those results.

### P2 — should-fix: the parity landing asserts an unmeasured performance guarantee

**Location:** `docs/research/agent-platform/landing/repl-parity-2026-09-26.md:91`.

**Failure scenario:** “No timing regression is possible” is used to waive the parent/change measurement. The combined path includes wrapper generation on a different thread and the armed evaluator, not just the cited `isa?`. Even a small source edit does not prove end-to-end performance, allocation or contract cost.

**Smallest fix:** replace the impossibility claim with an explicit unmeasured limit, and attach the same ordinary error/require probes timed on parent and combined change when root supplies P1's evidence. Historical bare-SCI microbenchmarks may remain separately labeled.

### P3 — nit: the SCI landing's net source count is arithmetically wrong

**Location:** `docs/research/agent-platform/landing/fix-sci-fork-vars-2026-09-26.md:74`.

**Failure scenario:** the change is reviewed as net +12 source lines even though the three listed source deltas are +6/−3, +9/−2 and +6/−2: **+21/−7, net +14**. That understates the actual size used for the shrinking-law assessment.

**Smallest fix:** correct +12 to +14 and retain tests as a separate count. The adjacent per-file numbers are already sufficient evidence.

## Source and dependency assessment

`kernel/execution-error-text` retains `clojure.main/ex-triage`/`ex-str` and only substitutes location for SCI's typed wrapper. This removes the prior conflation of arbitrary user `ex-data` with interpreter source metadata. SCI constructs that wrapper and applies its main-thread rule at `reference-code/sci/src/sci/impl/utils.cljc:121`. `d9566ad05` associates `:main-thread-id` on the per-evaluation context value immediately around `sci/eval-form`; it does not mutate the shared context or add another error formatter.

Returning `evaluated-value` instead of a persisted namespace row's name keeps storage separate from observation. It restores both `require` returning nil and a `do` returning its final expression without adding a special-case evaluator. Removing `context-row` from the intermediate result retires the now-unneeded alternate result channel while retaining namespace persistence.

The fork fix uses the existing ownership decision in `reference-code/sci/src/sci/impl/utils.cljc:368`: equal generation means the actual Var is owned; otherwise `owned-var` resolves/copies the inherited binding. Stamping `init-var!` at `reference-code/sci/src/sci/impl/analyzer.cljc:823` prevents the definition from binding a copy of a Var already captured during analysis. The shared constructor `new-local-var` at `reference-code/sci/src/sci/impl/namespaces.cljc:816` prevents anonymous letfn Vars from entering the inherited namespace-copy path. `generation-meta` at `utils.cljc:359` omits the stamp for an unforked environment, preserving upstream metadata behavior. This changes the maintained fork's producer instead of adding compensating branches to Seon's evaluator.

The new regression is meaningful: it checks a real fork's generation and executes letfn/protocol/record forms through Seon's evaluator. SCI's regression also checks a grandchild, inherited function creating locals, declare/definition binding and parent isolation. The thread-crossing error regression addresses the actual mismatch that same-thread tests missed. These are relevant behaviors, not assertions mirroring the implementation.

Malli's actual instrumentation seam (`reference-code/malli/src/malli/core.cljc:2202`, pin `befcd302a`) still requires the installed contracted path to pass. clj-kondo's `analyze-input` returns analyzed definitions/calls and linter findings (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:4452`, pin `57252e079`); clean lint cannot prove fork execution or reload. No Datahike, konserve, fipp or Flow mechanism is replaced by these fixes. Their shared runtime remains outside this small cut.

## Size and measured-evidence limits

The two Seon implementation commits sum to source **+30/−13 (net +17)** and tests **+33/−14 (net +19)**. SCI adds **+21/−7 source (net +14)** and **18 test lines**; the gitlink commit adds **14 Seon test lines**. No new registry, cache, atom or parallel REPL path is introduced. The small growth fixes named broken producer seams and removes namespace-row substitution; it does not claim unrelated deletion credit.

The SCI landing reports fork cost 0.68/0.61 µs before versus 0.68/0.68 µs after, and fork-plus-definition 152/156 µs versus 158/163 µs. It reports 399 tests, 1,502 assertions and five failures also present on its parent. These are attributed historical measurements, not a new pass or an entirely green suite. Installed Seon cost and attributable memory remain unverified. P1/P2 must be closed before treating the combined change as proven live; no source redesign is requested.

**ACCEPT-AFTER-FIXES**
