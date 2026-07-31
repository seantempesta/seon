---
type: research
status: active
tags: [research, agent, testing]
---

# MVP graduation eval implementation notes

## Program boundary

The earliest unsettled runtime contract remains source/publication/database-row
agreement (`plan/unsettled.md`, WORKING EDGE). This unit does not edit that
owner. It builds the separately designed context-MVP graduation eval offline
first, so the scorer discrimination proof is ready before the repaired boot
path authorizes a live sample.

The integrated proof for this unit is the recurring pytest discrimination
gate: every scenario check passes against a constructed good fixture snapshot
and fails when its own named bad state is introduced. The next program gate is
one local-Ollama scenario-B sample if and only if an isolated `bin/seon start`
can reach the advertised prepl after the offline gate passes. DeepSeek N=8 is
not authorized by this implementation unit.

## Dependency ledger

- The suite contract is
  `plan/mvp-graduation-eval-2026-07-31.md`: scenarios A--F, B's paired control,
  database predicates, nonce law, scratch-cluster lease, and the mandatory
  offline discrimination gate.
- The current agent curriculum and explicit-walk contracts are owner rulings
  #12 and #13 in `plan/README.md:1478-1533`.
- Existing Python check/scorer/frozen/live shapes are
  `src-inspect-ai/src/seon_inspect/product_scenarios.py:417-431` and
  `src-inspect-ai/src/seon_inspect/tasks/product_scenarios.py:78-247`.
- The retired branch/pod mechanisms remain quarry only. The surviving
  socket framing precedent is `parse_wire_json` / `wire_repl_json` at
  `src-inspect-ai/src/seon_inspect/cluster.py:686-715`.
- The fresh operator is `bin/seon` -> `seon.fresh-operator`. Its operator root
  comes from `--seon-root`; cluster paths derive from
  `script/seon/fresh_operator.clj:34-54`, not from the old
  `SEON_CLUSTER_DIR`/`SEON_PROC_DIR` selectors.
- The admitted isolated-root construction is
  `test/seon/dev/fresh_operator_test.clj:25-42`: a repository-local private
  root symlinks source/config inputs back to this checkout while owning its
  own `data/clusters`. The live reitit proof used the same construction
  (`research/reitit-adoption-notes-2026-07-31.md`, "Live scratch-cluster
  proof").
- The advertised endpoint schema and process-liveness check are grounded in
  `script/seon/dev/mcp.clj:115-180`; the io-prepl event reader is
  `script/seon/dev/mcp.clj:276-451` and Clojure's implementation is
  `reference-code/clojure/src/clj/clojure/core/server.clj:228-296`.
- The ordinary live inbound-message transaction is demonstrated by
  `test/seon/cluster/program_restart_test.clj:18-30`: one
  `:db.fn/call` to `seon.cluster.message/inbound-tx` over the selected
  branch connection. Agent creation uses `seon.cluster/ensure-entity!` at
  `src/seon/cluster.clj:899-909`.
- Test discipline comes from the current `clojure-testing` and `repl` skills:
  isolated fixture state, both correctness rails, event readiness, and a
  complete prepl envelope rather than a bare value guess.

## Isolation finding

Invoking the checkout's `bin/seon` directly cannot isolate this suite: that
script resolves its own repository root and passes it as `--seon-root`.
Therefore the lease creates a repository-local temporary operator root,
symlinks the runnable checkout inputs (including `bin/seon`) into that root,
and invokes that root's script. It then verifies that the advertisement path
is below the private root before opening the socket. A shared-root
advertisement is a harness failure, never a usable fallback.

Recursive teardown must unlink the private-root symlinks rather than follow
them. The lease cannot remove, reset, stop, or otherwise operate on the shared
checkout's clusters.

## Implementation ledger

- Scratch-cluster lease and prepl channel: landed initially in `7f5018031` and
  `fbac66fb7`, then adversarially tightened in the integration commit. The
  lease owns one repository-local operator root, delegates stale-process
  reconciliation to the JVM operator's exact `ProcessHandle` check, uses the
  advertised io-prepl, verifies the named running instance, supports one
  private sparse config manifest, and tears down without following symlinks.
- Scenario A--F checks and discrimination fixtures: landed initially in
  `81fc126b0`, then tightened after two read-only adversarial audits. The final
  scorer requires the walk-selected A projection, phase-scoped D evidence and
  observed commit ancestry, SCI's actual shared base-Var identity plus a
  peer-agent guarded arithmetic probe for E2, flat error values joined to
  their exact `(run-id, ordinal)` rather than synthesized refusals, proc
  provenance for core faults, and shared nonce/agent/episode identity for F.
- Frozen/live tasks, deterministic seed forms, and local-only smoke surface:
  landed initially in `ef4bcf034`, then integrated with the repaired lease.
  Episode completion now waits until `seon.cluster.work/next-agent-work`
  derives no continuation, and the immutable readback is restricted to the
  exact run ids returned for that phase. The smoke entrypoint refuses every
  paid arm, scenario other than B, and count other than one.

## Offline discrimination evidence

The recurring gate is:

```text
src-inspect-ai/.venv/bin/pytest -q \
  src-inspect-ai/tests/test_mvp_graduation.py \
  src-inspect-ai/tests/test_mvp_graduation_tasks.py \
  src-inspect-ai/tests/test_seon_cluster.py
```

Final result: **93 passed**. The direct fail-closed replay reports 53 named
correctness checks and 23 named taxonomy states. Every golden fixture snapshot
passes, every named check's single-field mutation fails that check, and every
taxonomy fixture is observed and scores incorrect. Generated wait/snapshot
forms are reader-checked through Babashka; the sentinel wrapper is executed,
not merely string-matched.

The wider retired `src-inspect-ai` suite is not a current repository gate:
594 tests passed, 8 skipped, while 18 failed and 10 errored in the old
oracle/tool-evaluator paths because their evaluator bundle returned no output.
The graduation modules do not import or restore those dead paths.

## Local smoke boundary

Boot repair commits `89874aaec` and `81e657ecb` landed, `bin/seon status`
succeeded, Ollama `0.32.1` answered locally, and the exact configured
`qwen3.5:35b-a3b-coding-nvfp4` model was installed. The one authorized B/N=1
command was attempted. `seon_inspect.source_admission` refused before scratch
cluster creation because the shared checkout contained dirty evaluation
source, including concurrent changes in `resources/seon/schema/ai.edn`,
`resources/seon/schema/context.edn`, `src/seon/cluster/loop.cljc`,
`src/seon/context.clj`, and `src/seon/render/ns.clj`. That is the exact
boundary: no local model call occurred, no DeepSeek call occurred, and the
guard was not bypassed or weakened.

The final owned integration is commit `40d0fcf7b`. The initial coherent
commits remain separately visible so the lease, scorer, and task construction
can each be reviewed before the adversarial repair diff.
