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

## Identity-slice observations, 2026-09-09 08:52 UTC

The HEAD-only bootstrap probe reports **8 tests / 45 assertions, 5 failures
and 1 error**, unchanged by the stable-id edits. The error is
`drive-free-generation-is-pure-deterministic-and-pull-gated`: the new
`ordered-episode` contract rejects the fixture's `:seon.repl/entry`.
The remaining failures expect the former generated opening membership and
supervision source. Exact logs and counts are in the
[landing note](../../prds/context-generation/research/turn-rename-landing-2026-09-09.md).

`test/seon/test_support.clj:624` claims `seed-cluster!` seeds a complete
config path, but writes only `{:seon.config/cluster cluster-name}` before
ensuring the cluster entity. The evaluate-sources fixture subsequently
failed armed with 69 missing effective-config fields. That caller now
installs `config/compile-manifest`'s desired row, and its real SCI proof
passes. The shared fixture was excluded from this lane's ownership;
its misleading completeness claim and other callers still need review.

The obsolete SCI test that restored earlier result objects from print-node
facts was deleted under turn PRD §14–§15. Persistent-context continuity and
fresh-context loss are covered by the real virtual-turn fixture. The other
legacy transcript expectations listed below remain unconverted.

## Acceptance criteria

Use canonical populated databases, real SCI/procs for execution proofs,
exact terminal facts for waits, and the declared event bound for arming.
Every future and graph must finish cleanup before restoring instrumentation
or returning to a worker. Observation failures must fail explicitly, never
satisfy a completion predicate. Keep error-data decoding separate from
saved shown text and live result-object inspection. Gate each changed
consumer namespace before claiming its conversion complete.

## Rename-slice observation, 2026-09-09 07:57 UTC

The extra `seon.render.transcript-test` fast probe reports 64 failures and
8 errors across the combined 67-test / 548-assertion invocation with
`seon.turn-test` and `seon.cluster.loop-test`. Every reported failure/error
is in the transcript namespace; its fixtures still supply removed result
EDN fields and expect supersession/history behavior retired by the PRD.
The provider reasoning test using the renamed component relation passes.
This is not a green transcript-suite claim. The rename gate remains the
explicit turn, loop, and web namespaces; repairing these observation
contracts belongs to the existing consumer-fixture issue.

Evidence: `tmp/turn-attempts-fast.log`; the rename landing note records the
committed source and the narrower required gate. Preserve the distinction
between actual shown text and old serialized-node fixtures when converting.
