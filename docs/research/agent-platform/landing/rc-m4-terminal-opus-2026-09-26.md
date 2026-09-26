---
type: landing
lane: rc-m4-terminal-opus
status: partial (M4 L1/L2/L3 committed f83931226; M3 settlement discriminators and L6 blocked on producers outside this lane; RESET NEEDED)
commits: f83931226
date: 2026-09-26
base: 373bfaf50 (default pid 48787 runs archive 20b156860)
---

# rc-m4-terminal-opus: landing

## Design (written before the edits)

What exists: `seon.turn/turn` dispatches one situation (`resume-turn` for agent forms). That calls
`evaluate-sources` → `analyze-submitted` → `settle-batch!` → `settle-batch-refusal!` on refusal. `phase`
turns an `ExceptionInfo` into a value; every other Throwable escapes to `step` and then to Flow's fault
route (`seon.flow/var-process`).

One owner per decision:

- **Run terminal: the turn boundary (`turn`).** On an escape it applies the writer's own recovery
  decision (`recover-tx`/`recover-call`, turn.clj ~1708), the one boot and disarm
  (`seon.cluster.agent/record-interruption!`) already use. Then it rethrows. The fault route stays the
  one fault.
- **Disposition: `seon.turn/disposition`.** It is total, and it is the loop's only classifier.
- **Commit success: the writer's transaction report** (`db/transact!` declares
  `[:or :seon.db/transaction-result :seon.db/transaction-refused-error]`, db.clj:4943). The test is
  `:db-after`, not a four-key refusal guess.
- **Publication status is separate from evaluated values.** A refused batch commit is every receipt's
  publication outcome. No ordinal is guessed.

Deleted decision sites:

- the failed-ordinal search and shown-text rewriting in `settle-batch-refusal!` (−27 lines);
- six four-key refused-outcome guesses (settle-batch!, settle-batch-refusal!, settle! ×2, record-attempt!,
  close-turn, plus agent.clj record-interruption!; −22 lines);
- the raw `(:my.turn/disposition value)` loop classifier in `evaluate-sources`.

## Broken things first

- **RESET NEEDED.** f83931226 is not live. Default runs archive 20b156860. None of the three regressions
  has run (load rule). Root runs them below.
- **M3 settlement discriminators (E6, task 1bf47dae6f04) are NOT done.** The three schema-guessing
  tables (`refusal-terminal-data`, `settle-batch-refusal!`, `record-attempt!`) can only go once every
  producer attaches `:seon.error/declared-schema`. Only `seon.fn` analysis, instrument and a few
  turn/schedule/boot producers do that today. The producers without it are in files this lane does not
  hold:
  - `seon.db` write refusal, invalid-read and schema validation: db.clj, one producer each;
  - eleven `seon.ai/*` refusals: ai.clj;
  - render walk/request/ambiguous/invalid-output/unknown: render.clj, render/walk.clj,
    render/transcript.clj;
  - `seon.config` error: config.clj;
  - `seon.fn/namespace-unresolvable`: fn.clj;
  - `seon.cluster.prompt` error: cluster/prompt.clj;
  - the turn.clj producers `refuse!` and `planned-sources` no-forms. These are mine, but deleting the
    tables before the rest converts would give a nil schema, which panics recording.

  Route: one scripted producer sweep (rc-m3-error-identity-opus or root), then delete the three conds in
  turn.clj (about −40 lines) in the same slice.
- **L6 (read-only heuristic, task ffb23155d2a0) is not done.** Replacing `read-only-evaluation?`
  requires writer effect evidence: a transaction sink per evaluation, like `db/*read-evidence-sink*`. That
  belongs in db.clj, not in this lane's files. No positive record of an evaluation's writes across
  connections exists to consume yet.
- **Visibility gap (MCP):** `script/seon/dev/mcp.clj:451` prints `(or shown error)`.
  - A refused batch now keeps each form's shown value and puts the refusal only in
    `:seon.cluster.eval/error`, so MCP shows the value flagged `:exception` without the refusal text.
    The transcript renderer shows both.
  - Root's choice: emit the error as its own `:err` event beside the `:ret` in `run-events`. It is
    duplicated for ordinary evaluation errors, whose shown text already renders the error.
- **Task 023acee5f88d is only partly addressed.** The ordinal guess is deleted. The issue's preferred
  design, where the refused program row becomes that one form's refusal through
  `sci.eval/refuse-install` at `evaluate-sources`, needs two things in sci/eval.clj (rc-m2 lane):
  - `refuse-install`'s contract widened beyond `:seon.test.accretion/install-refused-error`;
  - the analyzed row available before settlement.
- **Interrupts** (the stop's `InterruptedException`) are excluded from the boundary on purpose. Disarm's
  `record-interruption!` closes that run with the same writer function.

## Evidence

### Reproduction on HEAD (live default, MCP SCI, branch `rc-m4-terminal`, since retired)

| form | answer |
|---|---|
| `(+ 1 1)` | `2` |
| `(sorted-map 'a 1)` | MCP **timeout at 30 000 ms**; fault `fa60ad84…` `java.lang.ClassCastException` Symbol→Keyword at `seon.schema/valid-candidate-value?` via `turn-step` |
| `(+ 2 2)` (next run) | refused in 7 ms: `run transition refused: agent-already-running`. The agent is wedged. |

Cause: `disposition-rule-error` → `disposition` → Malli map validator keyword lookup
(malli core.cljc:1276) on a symbol-keyed sorted map. It throws outside `phase` (which catches
`ExceptionInfo` only) and outside any terminal owner, so the run stays open.

### The classifier, REPL on default (JVM, read-only, scratch fn with the new body)

Inputs:

- `(sorted-map 'a 1)`
- `(sorted-map "a" 1)`
- a keyword sorted-map disposition
- `{:completed …}`
- `{:my.turn/disposition :wait}`, which is invalid because it has no note
- `1`, `nil`, `[]`, `{}`

Results: `[nil nil <wait> <completed> nil nil nil nil nil]` in **0.175 ms** for all nine. The installed
`seon.turn/disposition` on `(sorted-map 'a 1)` still throws `ClassCastException`.

### Static checks

- `seon.contracts-compile-test/check` over turn.clj and agent.clj: `[]`, 83 ms.
- `every-declared-contract-compiles…`: pass 2.
- `every-packaged-schema-resource-reads-as-edn`: pass 211.
- clj-kondo: 0 errors; the 20 warnings are the same as at HEAD.

### Not proven here

- The boundary wiring through a live escape.
- The full sorted-map run after the fix.
- The alias-batch terminal.

The regressions carry these proofs.

## Changes (f83931226)

| path | net | what |
|---|---|---|
| src/seon/turn.clj | +85/−63 | total `disposition`; one classifier in `evaluate-sources`; `close-escaped-run!` + `turn` boundary; `settle-batch-refusal!` keeps evaluated values; `:db-after` success at five terminal sites; step/phase docstrings |
| src/seon/cluster/agent.clj | +1/−4 | `record-interruption!` reads `:db-after` |
| test/seon/turn_test.clj | +87 | two regressions + fixture |
| test/seon/cluster/agent_test.clj | +68 | end-to-end regression through `evaluate-source!` |

Source code net is about −9 lines (+45/−54 excluding comments and docstrings). The added docstrings
state the owner decisions.

## Root runs (after restart / adoption)

1. `bin/test-check default --test seon.cluster.agent-test/every-run-reaches-one-terminal-and-the-next-run-works`
   - sorted maps print `{a 1}` and `{"a" 1}`;
   - `(/ 1 0)` settles on its form;
   - the alias batch reaches a terminal;
   - the next `(+ 1 1)` → `2`.
   - If the printed map text differs, only the literal needs adjusting. The run-closed assertions are the
     class.
2. `bin/test-check default --test seon.turn-test/a-throwable-escaping-the-turn-closes-its-run-and-propagates-whole`
3. `bin/test-check default --test seon.turn-test/a-refused-settlement-keeps-each-evaluated-value`
4. The existing neighbours:
   - `bin/test-check default --ns seon.turn-test`;
   - `--test seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent`;
   - `--test seon.turn-test/virtual-turns-use-the-proc-and-compaction-is-agent-scoped`.
5. MCP acceptance on a fresh branch: `(sorted-map 'a 1)` → `{a 1}`, then `(+ 2 2)` → `4` (not
   `agent-already-running`).
