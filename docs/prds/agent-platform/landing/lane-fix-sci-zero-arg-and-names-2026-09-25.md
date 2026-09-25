---
type: landing
lane: fix-sci-zero-arg-and-names
status: complete
created: 2026-09-25
---

# Lane fix-sci-zero-arg-and-names: census class J (SCI regressions)

## Broken first

- **PLATFORM: call preparation is dead in default.** No elided connection or database is
  supplied anywhere. Live repro on MCP branch `fix-sci-zero-arg-and-names`: `(my.plan/plan)`
  fails with "argument count of 0" (77 ms).
  - The producer is outside this lane's files. Incremental publication leaves the stored
    `:seon.env/environment` shape stale after a dependency edit (d2604ede9's
    `:seon.flow/work-launcher`, tx 536878790).
  - Coherence then refuses both database suppliers: `(cp/snapshot db proj)` on default returns
    two `incoherent` refusals, "argument is not :seon.env/environment".
  - Issue: [a-schema-edit-leaves-dependent-shape-fingerprints-stale](../../../seon/issues/a-schema-edit-leaves-dependent-shape-fingerprints-stale.md).
  - The stored data needs republishing after the producer fix. **RESET NEEDED** (nuke/rebuild
    of default from committed inputs, or republication of the 25 stale schema rows and their
    arities).
- Suspect 2f4ea22d9 was **refuted**. The request branch's missing SCI program does not cause
  class J:
  - the adopted base commit already carries the split shapes;
  - an MCP branch acquires and resolves `my.plan/plan` and the call_preparation_test probes;
  - a fresh `cluster-ctx` resolves nothing until `acquire!` (lazy since 846bfd0f4, 09-21).

## What class J actually is (probed per sub-class)

| sub-class | cause | owner | done here |
|---|---|---|---|
| elided arg arrives as arity 0 (call_preparation_test 631/690/736/797, instrument sovereign fork `seon.id/valid?`, issue_test opening `help-value`/`my.issue/status`/`seon.plan/plan`) | stale env fingerprint → both db suppliers incoherent | publication (program.cljc / cluster publication) | class regression + issue |
| `refused-read` gets nil (call_preparation_test 265 absent-function, 364) | `plan-for` passed `(vals nil)` for an uncontracted identity | call_preparation.clj:1018 | **fixed** |
| "Unable to resolve" in `cluster-ctx` (call_preparation_test 826, 866) | tests evaluate raw on a lazily-acquiring ctx | test | **fixed** (acquire first) |
| `authored.target/increment does not name an installed SCI Var` (cluster/turn_test 1322) | the fixture writes `:seon.fn/sym` as strings (class C); the function rows never exist | turn_test.clj | not owned |
| retained branch loses its def (dev/mcp_bridge_test 682) | the uncontracted `defn` is refused by the `:gate` entry policy, so it was never defined | mcp_bridge_test.clj | not owned |
| `acquisition-refusal` string row-member (adoption_diagnostic_test 10) | the test passes `{:seon.fn/sym "adoption.probe/broken"}`, a string | test | not owned |
| error_test row-acquisition (76) | real-row probe returns row-member `seon.id/id` correctly; the test reads a returned value through `refusal-data` | error_test.clj | not owned |
| documentation_test ×5, branch_execution_test 412 (`seon.cluster/serve! :unavailable`) | unprobed | — | open |

## Evidence

- `seon.call-preparation/snapshot` on default takes 65 ms. It holds 197 prepared symbols, and
  `:seon.db/db` and `:seon.db/connection` are refused.
- Shapes for `:seon.env/environment`:
  - 18535, `eb3694…`: tx 536870917; the schema row plus 11 arities;
  - 90816, `9a103b…`: tx 536878790; `seon.db/supplied-*`.
  - The current projection derives `9a103b…` (`prepare-forms`).
- Supplying `9a103b…` to `coherent-supplier` makes the two db rows coherent. `:my.program/context`
  and `:seon.agent/id` are then refused, so no single fingerprint works.
- `(sci.eval/acquire! {ctx db})` on a fresh `cluster-ctx` takes 16 ms warm. After it,
  `my.plan/plan` and `seon.call-preparation-test/probe-received-connection?` resolve. Before it,
  even `seon.db/q` does not resolve.

## Changes

- src/seon/call_preparation.clj: `plan-for` calls `(refused-read (vec (vals reads)))`. An
  uncontracted identity is nil again, never a contract throw. +2/−1.
- test/seon/call_preparation_test.clj:
  - new `the-shipped-database-suppliers-are-coherent-in-the-executing-program`, the class
    regression for the stale-shape producer; it stays red until publication is fixed and
    default is republished;
  - the two acquired-ctx tests call `acquire!` before raw evaluation.
  - +19/−0.
- Net source +1, tests +19. No eval.clj edit: none of its sub-classes was a source defect in
  eval.clj.

## Options for root on the producer (new decision)

1. Simplest: republish default now (nuke/rebuild from committed inputs). The stored rows
   converge until the next dependency edit. Cost: one nuke. It repairs nothing lasting.
2. **Recommended.** The publication owner re-derives the shape of every schema and arity row
   whose reference closure contains a changed definition
   (`:seon.schema.projection/reverse-schema-dependencies`, schema.clj:985). Then option 1.
   Cost: one lane on program.cljc and cluster publication, about 30–60 lines.
3. Call preparation matches the supplier's environment argument by declared schema name
   (shape type) instead of by fingerprint. That is about 10 lines in call_preparation.clj.
   Other fingerprint consumers stay exposed, including instrument.clj:604 and the positional
   and map-entry joins. It hides the split.

## Root runs (serially, quiet window)

```
bin/test-check default --ns seon.call-preparation-test
bin/test-check default --test seon.instrument-test/a-sovereign-sci-fork-acquires-its-own-recorder
bin/test-check default --test seon.issue-test/issue-worker-opening-links-its-issue
```

Census J symbols to rerun after the producer fix and republication:
- seon.call-preparation-test:
  - a-compiled-first-party-call-is-prepared
  - a-two-slot-arity-prepares-only-unique-partial-placements
  - the-leave-off-the-database-shortcut-survives-the-general-planner
  - two-clusters-supply-their-own-custody
  - an-acquired-cluster-context-prepares-its-calls
  - an-acquired-context-supplies-a-leading-database-to-plan
  - a-row-published-after-a-warm-plan-is-seen-synchronously
  - an-unreadable-program-graph-is-not-an-uncontracted-function
- seon.instrument-test/a-sovereign-sci-fork-acquires-its-own-recorder
- seon.issue-test/issue-worker-opening-links-its-issue
- seon.cluster.turn-test/acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles
- seon.dev.mcp-bridge-test/a-lane-owns-its-branch-create-define-call-retire
- seon.adoption-diagnostic-test/row-refusal-describes-the-cause-without-copying-the-projection
- seon.error-test/row-acquisition-observations-have-no-program-digest-promise
- seon.sci.documentation-test:
  - a-contract-mistake-carries-the-same-documentation-as-doc
  - bare-test-macros-resolve-without-namespace-referrals
  - directory-observes-a-function-installed-after-context-acquisition
  - retained-context-receives-new-bare-test-referrals
  - retained-context-receives-the-current-repl-macro-through-its-core-alias
- seon.sci.branch-execution-test/interpreted-callers-resolve-unchanged-names-to-compiled-vars

## Limits

- No test was run (LOAD RULE). The changes are linted only (0 errors) and are not loaded into
  default.
- The call_preparation_test assertions that depend on supplied defaults stay red until the
  producer is fixed and the data is republished.
- The documentation_test and branch_execution_test sub-classes are unprobed.
- The probe branch `fix-sci-zero-arg-and-names` was retired.
