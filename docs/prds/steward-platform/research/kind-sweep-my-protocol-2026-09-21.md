---
type: research
status: complete
created: 2026-09-21
tags: [error-model, kind-retirement]
---

# Kind sweep — my protocol

Read the binding error-conversion PRD and the render, render-2, and
turn-cluster sweep notes end to end. This lane used the data-oriented-clojure,
clojure-testing, and repl skills. It did not operate the default cluster,
run a cold gate, or create a worktree. The Seon MCP status/evaluation tools
were not exposed to this session; `bin/seon status` reported the default
cluster alive, and the source/load/fast-gate evidence below is the exact
verification boundary.

## Result

Every `src/my/*.clj` and `test/my/*_test.clj` file is free of
`:seon.error/kind`, `seon.error/class`, and `error/error?`. The local
`my.background/invalid-call` producer now uses the leaf
`seon.error.refusal/diagnostic` constructor, carries the required diagnostic
base, and reuses the declared `:my.background/invalid-call-error` facet. The
local `my.program` producers likewise supply the base and retain their
declared distinguishing members. Filesystem, edit, shell, background, and
turn protocol contracts name their existing domain facets instead of the
generic base.

The stale SCI-boundary tests now recognize the instrumentation facet through
`:seon.instrument/check :input` and its `:seon.instrument/invocation` layer.
The fs/edit capability regressions now look up the already-symbolic
`:seon.effect/capability` without converting it to a string.

## Verification

The ordered discriminator and class-marker search returned zero lines across
all owned source and test files. No owned inline base-three consumer check was
introduced. The required load command printed `:loads`:

```text
clojure -M -e "(require 'my.message 'my.turn 'my.fs 'my.edit 'my.plan
  'my.note 'my.web 'my.background 'my.test 'my.program) (println :loads)"
```

The one full foreground overlay completed with **57 tests / 871 assertions /
68 failures / 19 errors**, run `74a1df801f63`. This is a total tally, not a
green claim. After correcting only the two changed instrumentation-facet
assertions, the permitted targeted rerun completed with **16 tests / 300
assertions / 0 failures / 4 errors**, run `a76c69196f3e`. Both formerly stale
`a-contract-forbidden-argument-is-a-value-the-agent-reads` tests passed.
The final plan-only rerun, required after replacing its remaining inline base
checks with typed base-member assertions, completed **22 tests / 123 assertions
/ 0 failures / 7 errors**, run `f961341468e9`; all errors are the held
`seon.plan`/`seon.db` output-contract boundaries listed below.

## Held-owner handoff

The remaining failures are exact held-owner boundaries, not edits for this
lane:

- `seon.cluster.message/send`, `decline`, `delivery`, and `send!` return the
  `:my.message/no-*` and `:seon.message/unknown-recipient` observations without
  a diagnostic base or without declaring those returned facets. The wrapper
  therefore refuses their output. Owner: `src/seon/cluster/message.clj`.
- `seon.run/complete` and `seon.run/wait` return the
  `:my.turn/blank-result-error` / `:my.turn/blank-note-error` observations
  without the diagnostic base and exact output facets. Owner: `src/seon/run.clj`.
- `seon.background/poll` and `await` inherit the held background producer's
  incomplete refusals. The background fixture also writes the now-symbolic
  `:seon.effect/owner` as a string. Owner: `src/seon/background.clj` and its
  fixture producer.
- `seon.plan/plan!`, `start!`, and database transaction pass-throughs return
  plan/database refusals that their current output contracts do not declare.
  Owner: `src/seon/plan.clj` and `src/seon/db.clj`.
- The remaining generic `:seon.error/value` outputs in `src/my/{agent,message,
  note,plan,program,test}.clj` cannot be narrowed truthfully until those held
  producers declare their exact unions. They remain explicit rule-1.2 handoff
  debt; no parallel facet was minted here.
- The full tally also observed existing example-fixture/documentation failures
  and an out-of-memory error while printing their evidence. Those paths were
  not changed or attributed to this sweep.

## Cold proof owed

The orchestrator owes:

```text
bin/test --paths src/my/background.clj src/my/edit.clj src/my/fs.clj
  src/my/program.clj src/my/shell.clj src/my/turn.clj
  test/my/background_test.clj test/my/edit_test.clj test/my/examples_test.clj
  test/my/fs_test.clj test/my/message_test.clj test/my/note_test.clj
  test/my/plan_test.clj test/my/program_mutation_test.clj
  test/my/program_test.clj test/my/turn_test.clj --
  my.background-test my.edit-test my.examples-test my.fs-test my.message-test
  my.note-test my.plan-test my.program-mutation-test my.program-test my.turn-test
```

followed by `bin/test --platform` after the held producer sweeps land.
