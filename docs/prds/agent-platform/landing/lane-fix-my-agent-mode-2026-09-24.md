---
type: landing
status: landed
lane: fix-my-agent-mode
created: 2026-09-24
issue: docs/seon/issues/agent-custody-defaults-to-the-cluster-label-branch.md
---

# Lane fix-my-agent-mode — landing (2026-09-24)

What exists: `seon.cluster.agent/acquire-context!` decides whether an acquisition is `:live` or
`:isolated` (`isolated? (not= branch live-branch)`, where `live-branch` is the parent
connection's `[:config :branch]`). It stores that decision as `:seon.agent/mode` on the
context-state entry `[branch agent-id]`, and the context's environment carries that
context-state. `my.agent/branch` now reads the decision from that owner. It gets the owner
through the existing `:my.program/context` supplied default (`my.program/supplied-context`
carries `:seon.db/connection` and `:seon.agent/context-state`). The cluster-label rule is
deleted: `[:find ?name .]` plus `registry/cluster-branch`.

## Broken things first

- **Mode is relative to the acquiring parent, not to the branch.** My first cut looked up
  "any entry on the held branch". It failed its own regression: a test member's context-state
  holds both its own `[member-branch nil] :isolated` entry and `[member-branch "mode-live"]
  :live`. Only the exact `[held-branch agent-id]` entry answers. That is what landed.
- **MCP `eval_clj` on a named branch now refuses loudly.** Example: `(my.agent/branch
  {:seon.agent/id "root"})`. The MCP entrance acquires the branch with a nil agent id
  (`seon.cluster.agent/acquire-context! source nil {:seon.agent/branch …}`), so no acquisition
  of "root" holds that branch. The refusal is "No acquisition of this agent holds its
  connection's branch." with `{:seon.agent/id "root" :seon.agent/branch :fix-my-agent-mode-2}`.
  Before the fix the same call answered `{:branch :cluster-default :mode :live}` while the
  context held `:fix-my-agent-mode`, which was false.
- The two other reds in `my.agent-test` are not mine. They were there before this change
  (landing lane-fix-agent-custody-label, diagnosis A):
  - `settings-are-one-owned-component-with-a-derived-render-pair`: a hand-built row lacks
    `:seon.agent/branch`.
  - `settings-updates-preserve-one-component-and-agent-isolation`.
- Ugly output, found in passing and not filed: when `seon.env/supplied-agent-id` refuses, it
  puts the whole environment (ctx, projection, store) in `:seon.error/offending`. Rendering
  that took 2.5 s (the `seon.error/render-ai` share of a 3037 ms evaluation).
- My first save refused at the gate on a foreign red: 223 errors in
  `seon.cluster.agent-test/with-connection` ("Symbol cannot be cast to LocalBinding"). A later
  save of the test file published and adopted both files.

## Evidence

- REPL, before, on MCP branch `fix-my-agent-mode`: `(my.agent/branch {:seon.agent/id "root"})`
  answered `#:seon.agent{:branch :cluster-default, :mode :live}` while the context held
  `:fix-my-agent-mode`. That shows the inherited-row plus label rule.
- REPL, after, on the shared default context (sci): root answered
  `{:branch :cluster-default :mode :live}`. It took 309 ms of evaluation, most of it evaluate
  overhead; the evaluation allocated 1.07 GB.
- REPL, after, on MCP branch `fix-my-agent-mode-2`: the loud refusal above. Warm evaluation
  took 40 ms (3 ms before, when it answered the false value).
- Regression `my.agent-test/branch-mode-is-the-acquisition-decision-not-a-cluster-label`.
  It checks two agents created on the test member's branch, which inherits default's cluster
  rows:
  - one acquired live reads `{:branch parent-branch :mode :live}`;
  - one acquired with `:seon.agent/isolate? true` reads its own fresh branch and `:isolated`.
  - Under the first cut (branch scan) it failed: the live agent read `:isolated`. The branch
    scan and the label rule both give that wrong answer on a member branch, because the
    member branch is not `:cluster-default`.
  - The save gate passed it (run 000d3d3d801a, 3/0/0, 5389 ms test phase) and adopted commit
    6ab570eb. `bin/test-check default --ns my.agent-test` reused it green (run 2bec011320ec).
- `seon.cluster.acquisition-test/acquisition-owns-only-its-isolated-resources` also calls
  `(my.agent/branch)`: agent a is isolated and agent b is live. `bin/test-check` excludes it as
  platform-only, so root must run it (`bin/test --platform`). I did not run it.
- Outputs: `tmp/fix-my-agent-mode/my-agent-test.txt` and `acquisition-test.txt`.

## Change

- `src/my/agent.clj`: +19/−12. The request is now `[:map [:seon.agent/id …]
  [:my.program/context …]]`, and the `seon.cluster.registry` require is gone.
- `test/my/agent_test.clj`: +40, add-only (one helper and one deftest).
- Net source is +7 lines.

## Verification limits

- The platform acquisition test was not run (see above).
- No bulk or platform tier was run. No RESET NEEDED.
