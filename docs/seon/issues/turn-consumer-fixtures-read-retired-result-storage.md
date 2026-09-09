---
type: issue
status: open
severity: friction
tags: [issue, test, runtime, class/p3, wave/contract-gate]
---

# Turn consumers retain obsolete fixture and observation contracts

## Evidence, 2026-09-09

At `34e47f595`, `test/seon/cluster/work_test.clj:122` and `:177` write
`:seon.cluster.eval/result-edn`, which the canonical schema no longer
installs. Its comment-only query at `:410` reads the same deleted
attribute. The writer refuses the fixture transactions, leaving evaluations
unfinished. The armed run reports six assertion failures in the state
table/property and the generated/comment-only tests. The scoped fix uses
the installed shown-text field, `:seon.eval/value`, without changing the
asserted work states or deleting their tests.

The larger custody consumer conversion remains unfinished:

- `test/seon/cluster/agent_test.clj:1287` awaits a query against the
  retired result field using `some?`. A typed read error satisfies that
  predicate before the turn closes. The ensuing assertions fail, and
  printing a non-nil fault containing runtime objects raised
  `OutOfMemoryError: Required array length 2147483640 + 18 is too large`.
- The prompt-refusal regression at `:630` removes the old context
  channel, which the prompt request no longer requires. A HEAD-only
  snapshot independently reproduced its two assertion failures. A refused
  prompt does not answer its wake; the settings component's turn bound
  must determine subsequent work. An unverified conversion is preserved
  in the stash named in the landing note.
- `routing-trial` waits on `armed-event` using unbounded `async/<!!`.
  Its outer routing test can time out and return while a future still owns
  temporary Var roots. The gate reported three missing wrappers after
  `routing-conservation-waits-for-terminal-evidence`: `seon.ai/complete`,
  `seon.bootstrap/next-entry`, and `seon.sci.eval/evaluate`.
- `test/seon/cluster/turn_test.clj` mixes stored shown text and the still
  separate error-data blob codec in `semantic-result`. A global switch to
  EDN decoding is invalid: shown Var text is not EDN, and error data still
  needs its own existing codec. Tests of deleted private storage also
  query `:seon.def/agent`. Do not mechanically turn either failure into a
  literal nil or an empty success.

Logs and rejected-draft identity are recorded in
[the landing note](../../prds/context-generation/research/turn-rename-landing-2026-09-09.md).
These are local fixture/consumer boundaries, not another lane's failure.

## Acceptance

Use canonical populated databases, real SCI/procs for execution proofs,
exact terminal facts for waits, and the declared event bound for arming.
Every future and graph must finish cleanup before restoring instrumentation
or returning to a worker. Observation failures must fail explicitly, never
satisfy a completion predicate. Keep error-data decoding separate from
saved shown text and live result-object inspection. Gate each changed
consumer namespace before claiming its conversion complete.
