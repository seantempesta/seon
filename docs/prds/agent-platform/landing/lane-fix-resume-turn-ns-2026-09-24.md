---
type: landing
status: landed
created: 2026-09-24
tags: [agent-platform, turn, contracts, disposition]
---

# Lane fix-resume-turn-ns — 2026-09-24

The turn loop evaluates a reply's forms in `seon.turn/evaluate-sources`, and
`resume-turn` settles them in one transaction through `settle-batch!`. When the
disposition rule refused a form, the loop replaced that form's evaluation with a
hand-built three-key map. The fix builds the replacement with
`seon.sci.eval/unrun-evaluation`, the one existing constructor of an evaluation
settled without its result.

## What is still broken or unproven

- **The fix is not installed on default.** Pid 90471 runs archive `ce7384682` with
  hook publication off. **RESET NEEDED** (or `start --head`) to install it.
- **The regression is red on its duration bound.** Its 7 assertions pass, but it runs
  14.1–18.0 s against the 5 s bound (4 runs in the scratch root). Measured inside
  the body: `seed-cluster!` 6,962 ms, `fork-cluster-ctx` 18 ms, `next-agent-work`
  22 ms, `resume-turn` 16,744 ms. Profile: `seon.schema/call-with-projection-state`
  ×2 took 32,259 ms inclusive, and `seon.db/call-with-custody` ×3 took 56,791 ms
  inclusive. This is the existing platform cost in
  `docs/seon/issues/a-one-form-resume-turn-takes-seconds.md`, not this change. I
  did not raise the bound. Work over 10 s would need owner authorization.
- **No red-before run of the new test.** The first draft drove the whole agent graph
  (`fixture/install!` plus `submit!`). It never finished inside the 120 s request
  bound, on either the parent or the fixed code. `seon.rereads-test`, which uses the
  same harness, did the same (`tmp/fix-resume-evidence/fix-resume-red*.log`). Each
  run that timed out left a `:cluster-<name>` branch in the store, and that made the
  next run with the same name refuse with "Isolation requires a fresh owned
  branch". I tried to get a red run by gating HEAD's `turn.clj` back in. It reached
  adoption and was refused as "Source changed during development adoption". Most
  likely no test was selected: the test reaches `resume-turn` only through
  `#'turn/resume-turn`, and the ledger already records that call-edge selection gap.
  The "before" evidence is therefore the stored production refusal below.
- **Semantic stretch.** The docstring of `unrun-evaluation` says the form was settled
  without running. A disposition-refused form did run, and its effects stand; only
  its result is replaced. The ending namespace becomes the form's own namespace.
- `test/seon/loop_proof_test.clj:923-928` asserts the same message for
  `(let [] (my.agent/done))`. That case goes through the same producer. I don't
  own that file and did not run it.

## Evidence

Stored refusal on default, error occurrence 62511, turn 62279 (`0b022754e9f7`), 2
occurrences, 2026-09-23 16:44:13–14Z, process `27531-1790181684235`:
`seon.turn/settle-batch! refused requests at [0 :seon.sci.eval/evaluation :seon.cluster.eval/ns]`.
It has four explanations, each with an actual value of nil:
`:seon.cluster.eval/ns`, `:seon.sci.eval/ending-ns`, `:seon.print/options` and
`:seon.sci.admit/record`. These are exactly the required keys of
`:seon.sci.eval/evaluation` (`resources/seon/schemas/seon.sci.eval.edn:57-64`) that
the hand-built map at `evaluate-sources` omitted.

The row's framing was that `resume-turn` built the request without a namespace. The
stored facts refute that:
- The turn's stored evaluation carries `:seon.cluster.eval/ns my.agents.root`.
- The missing key is inside the in-memory evaluation, not on the request.
- The turn's only source is `['(seon.db/q …) (my.agent/done)]`. That is
  `(my.agent/done)` nested in a vector, so `disposition-rule-error` refuses it.

Probe (MCP JVM on default, read-only, armed contracts):
- `disposition-rule-error` on `"['(+ 1 2) (my.agent/done)]"` returns the
  invalid-disposition error.
- `sci.eval/unrun-evaluation` builds a complete, contract-valid evaluation from that
  error in 0.042 ms. Its error text is "(my.agent/done) must be the last form of your
  reply; …", and it carries the namespace, ending namespace, `{}` print options and
  an unarmed record.

That constructor runs only on the refusal path, so no hot path changes. Before: a
literal map, then a refused batch. After: 0.04 ms.

Installed proof: scratch root booted from a `git archive` of HEAD `43addd46c` plus
this diff, with `reference-code` and `target` linked (boots took 154–218 s from zero).
`bin/test-check --root <scratch> default --ns seon.turn-disposition-test` ran
7 pass / 0 error, and the only fail was the duration bound (runs `092010d4d1e4`,
`27913ec22065`, `d5f0669d65e9`, `1fbc9a7087e5`). The test goes through the real
resume path:
- the canonical `open-tx` and `plan-tx` writers;
- `turn/next-agent-work`, which derives `:resume` at ordinal 0;
- `#'turn/resume-turn` with real SCI and armed contracts.

It asserts that:
- the batch settles;
- the stored evaluation has the disposition error, shown text and its namespace;
- no `settle-batch!` refusal is recorded.

Logs are in `tmp/fix-resume-evidence/`. The scratch root and snapshot are deleted.

## Changes

- `src/seon/turn.clj`: +10 / −3.
- `test/seon/turn_disposition_test.clj`: new file, 72 lines.
