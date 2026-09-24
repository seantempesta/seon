---
type: landing
status: partial
lane: fix-agent-custody-label
created: 2026-09-24
issue: docs/seon/issues/agent-custody-defaults-to-the-cluster-label-branch.md
---

# Lane fix-agent-custody-label — landing (2026-09-24)

What exists: `seon.cluster.agent/creation-tx` writes an agent row. `acquire-context!` then follows
that row's `:seon.agent/branch`. The fix lets the writer decide custody (a `:db.fn/call`, the
same pattern as `steward-call`) and gives each isolated world its own context state.

## Broken things first

- **`seon.cluster.turn-test` refusals did not go down: 27 before, 30 after.** Its fixtures
  write the label branch into rows by hand: `(registry/cluster-branch "turn-test")` at
  `test/seon/cluster/turn_test.clj:109,138` (`agent-row`) and at fifteen sites in
  `test/seon/turn_test.clj`. One of those sites is agent "c" at :655 in the virtual-turns
  fixture, which still refuses. None of these files are mine. The fix is one scripted sweep
  to `(get-in @connection [:config :branch])` or to `creation-tx`, recorded in the issue.
  The two runs executed different member sets (33 and 38), so the raw counts are not a
  like-for-like comparison.
- The publication gate that reaches `creation-tx` stays red for reasons outside this change:
  - 22 hand-built agent rows lack `:seon.agent/branch` (`my.plan-test` "alice",
    `my.agent-test` "settings-owner", `seon.agent-call-edges-test`). Diagnosis A in
    e9c636081 already covers these.
  - `my.message-test` and `my.note-test` have their own reds.
  - A foreign `branch_execution_test.clj` analysis refusal appeared at 18:20Z.
  - One degraded gate at 18:41Z: `seon.test/green?` refused a result missing `:seon.test/run-basis-t`.
- Test-check runs refused twice with `:seon.test/program-mismatch` while other publications
  were in flight. A retry succeeded each time.

## Evidence

- REPL (jvm, scratch ns, fresh parent atom): inside `:db.fn/call` on an isolated member, the
  database's `[:config :branch]` is the member branch (`:agent-06ecfc88410a`). The old
  `creation-tx` recorded `:cluster-custody-probe` there. With `branch-call` the row records
  the member branch. Live `root` row: `:cluster-default`. Live context state holds
  `[:cluster-default "root"]` in `:live` mode. That is the entry a member sharing the atom
  would have borrowed.
- Regression `seon.cluster.agent-custody-test/an-agent-lives-on-the-branch-its-creating-connection-holds`
  - After the fix: pass 9/0/0, run 746de553adaa, 3571 ms.
  - With HEAD's `agent.clj` restored temporarily: fail 5, error 1 (run 097d5ff2d925). The
    recorded branch was `:cluster-default`, not the member's. The parent's acquisition
    refused. The child shared the parent's context state.
- `seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion` no longer
  refuses on isolation. It now fails later, on its own 77 s budget and time limit (run 53708f5984d8).
- Isolated acquisition probe: 254 ms before, 298 ms after. Each is a single sample. The
  added work is one atom allocation, and the difference is under the 50 ms threshold.
- Outputs are in `tmp/fix-agent-custody-label/`: turn-test-before.txt, turn-test-after.txt,
  custody-without-fix.txt, named-after.txt and publication-gate-*.txt.

## Change

- `src/seon/cluster/agent.clj`: +37/−20.
  - New `branch-call`, contracted.
  - `creation-tx` no longer derives a branch from the cluster name.
  - `acquire-context!` gives an `:seon.agent/isolate?` acquisition `held-contexts` (a fresh
    atom), used for the environment, the handle and its own entry.
- `test/seon/cluster/agent_custody_test.clj`: new, 57 lines.

## Verification limits

- The regression acquires but does not `arm!`. `arm!` reaches the same `acquire-context!`
  call (agent.clj `arm!` → `acquire-context! handle agent-id`).
- "Live default unchanged" is proven against the test's parent world, not the real
  `:cluster-default`.
- A member's inherited `root` row (`:cluster-default`) now refuses loudly with "Isolation
  requires a fresh owned branch." instead of borrowing live root. Running inherited agents
  inside a member is a separate design question.
- `src/my/agent.clj:73` still derives mode from `registry/cluster-branch`. That is the same
  class of defect, and the file is not mine.
- No bulk or platform tier was run. No RESET NEEDED.
