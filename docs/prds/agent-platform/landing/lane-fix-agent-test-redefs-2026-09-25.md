---
type: landing
status: complete
lane: fix-agent-test-redefs-opus
created: 2026-09-25
---

# Agent test: no evaluator redefinition (census 2026-09-25c, StackOverflowError x11)

`with-connection` in test/seon/cluster/agent_test.clj redefined `seon.sci.eval/evaluate` with `with-redefs`. That is
a root change visible to the whole JVM. The fixture also captured the current root as its "real" evaluator. The fix
deletes the redefinition, so the shipped evaluator runs as installed.

## Broken or unproven first

- **No test was run.** The lane follows the LOAD RULE. Every behavioural claim below waits for root's runs.
- **Why `wake-routing-conservation-property` never exited is inferred, not reproduced.** Verified facts:
  - `(:seon.config.ai/no-provider seon.config/defaults)` is `true` in the live JVM (MCP probe).
  - Before this change, `routing-trial`'s config row did not retract that dial. `provider-backstop-config` does
    (aedca57d3).
  - When the dial is set, turn.clj:4479 freezes `{:seon.ai/text ""}` before the provider. The `ai/complete`
    stand-in is never reached.
  - `routing-conservation-waits-for-terminal-evidence` blocks on that stand-in (`provider-entered`). Its recorded
    red is "did not exit ... remainder 21008 ms" (run ed729f0e138c).
  - test.check 1.1.1 `shrink-loop` (check.cljc:242) has no time bound.
  - Each failing trial waits at least the 20 s event backstop (test_support.clj:30). It then pays a second backstop
    in cleanup, awaiting `::routing-armer-stopped`.

  Inference: a failing trial, shrunk without a time bound at ≥20 s per step, outran the 120 s check limit. Unproven:
  whether an empty no-provider reply leaves the routing trial unquiescent. Root's run below decides it.
- **The stale Var `seon.cluster.agent-test/fixture-evaluate` is still interned in default.** `require :reload` does
  not unmap it. Root adoption or reindex should retire its program row. Nothing references it.
- **The bound is recorded as its mechanism, not as a measured number.** No `:seon.test/long-ms` was added, because no
  measurement exists. Root measures, then sets `:seon.test/long`/`:seon.test/long-ms` if the pass exceeds 5 s. A pass
  over 10 s needs an issue under the >10 s rule.
- **Out of scope, same law class.** This file still uses `with-redefs` over default's Vars `ai/complete`,
  `prompt/prompt`, `async/close!`, `turn/next-agent-work`, `turn/turn` and `bootstrap/next-entry`, plus
  `alter-var-root #'seon.turn/step` (about :1590). Each one is a JVM-global stand-in. A body that does not exit leaks
  it the same way. Removing them needs owner injection seams: the provider completer on the cluster handle, and
  prompt. That is not a test-only change. Root should file or link this under the census class.

## Change

- Deleted `real-evaluate`, `fixture-evaluate`, the evaluator arity of `with-connection`, the `with-redefs` over
  `sci.eval/evaluate`, and the `seon.sci.eval` require. Two `(with-connection real-evaluate f)` callers became
  `(with-connection f)`.
- `restamp-recovery-test`: removed its evaluator observation. The assertion it fed, `not-any? "my.message/send"`,
  could never fail, because the frozen suffix source is `seon.cluster.message/send`. The existing database assertions
  already prove the suffix never ran: the ordinal-2 evaluation has no `:seon.eval/shown` and no
  `:seon.cluster.eval/error`.
- `routing-trial` config row: `:seon.config.ai/no-provider :seon.config/absent`, because its completer stand-in is
  the provider.
- `wake-routing-conservation-property`: a comment beside the test records its failure-cost mechanism.
- Owner seam: `seon.turn`/`seon.cluster.agent` have no evaluator injection member. None is needed, because no test
  observes the evaluator any more.

## Evidence

- `clj-kondo --lint test/seon/cluster/agent_test.clj`: 0 errors, 11 warnings. HEAD has the same 11 warnings.
- `(require 'seon.cluster.agent-test :reload)` in default: 107.7 ms, loads. Afterwards
  `(class seon.sci.eval/evaluate)` is still the instrument wrapper `clojure.lang.AFunction$1`.
- Net lines: +28 / −48 in the test (−20). No source changes.

## Root runs (serially, in a quiet window)

1. `bin/test-check default --test seon.cluster.agent-test/wake-routing-conservation-property --include-long`: record
   the elapsed time and set its bound from it.
2. `bin/test-check default --test seon.cluster.agent-test/routing-conservation-waits-for-terminal-evidence`
3. `bin/test-check default --test seon.cluster.agent-test/restamp-recovery-test`
4. `bin/test-check default --ns seon.cluster.agent-test --include-long`
5. After step 4: MCP `eval_clj` `(class @#'seon.sci.eval/evaluate)` should still be `clojure.lang.AFunction$1`, and
   the error list should show no new `fixture-evaluate` StackOverflowError.

No RESET NEEDED.
