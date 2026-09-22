---
type: research
status: incomplete
created: 2026-09-21
---

# B3 kind/class cut — landing evidence

**Kind/class and caller conversion complete; final namespace verification in progress.**
The requested scan is empty. Seven earlier-contract debt boundaries are listed below.
No final green or live default settlement claim is made.

## Commits and changed paths

- `f7f5c36ef`: class metadata retirement and native schema parity (39 paths),
  including deletion of `error_class_schema_test`. Prescribed load exited 0.
- `b730705ef`: operator script and hook caller conversion (8 paths).
  Prescribed load exited 0.

### Final settlement verification, 2026-09-22 07:00 UTC

The four-namespace turn request completed `seon.cluster.turn-test` (60 tests,
33 reported failures, 58 errors), `seon.turn-test` (34 tests, 9 failures,
16 errors), and `seon.turn-loop-test` (26 tests, 7 failures, 9 errors).
It then reached the installed no-progress bound in
`seon.turn-continue-test/comment-only-refusal-arrives-before-done` and exited
124. These are observed event counts, not a recorded green result.

That run exposed an incomplete base value in the existing `phase` exception
translation: ordinary `ex-data` lacked the members required by the settlement
failure contract. `phase` now supplies `at`, `layer`, and `operation` before
merging the exception's refusal data. It retains `phase-failed true` and the
original exception message and members. This is the existing exception boundary,
not a new propagation mechanism.

The subsequent request `3c3b9919d05d` ran `seon.cluster.turn-test` and
`seon.schema.datahike-test`: **74 tests, 218 assertions, 49 failures, 57 errors**.
The recording authority refused the result; no recorded green is claimed.
The no-forms regression completed at `06:56:54.902423Z`: its durable evaluation,
declared-attribute transaction report, and unparked-proc assertions passed, but
the required error text assertion failed. It stored `:malli.core/invalid-schema`
instead of the no-forms text. The canonical fixture still carries publication
`b771bf4fa00eea263a2b679e89aa58fce34471659c38a5c1e11c7c43786797e0`, while the
selected source requires the newly declared turn error schemas. The earlier
Slice A no-forms pass remains valid only for that slice. It is **not** a final-cut
pass. The fixture acquisition path explicitly copies the published store and
derives its projection from that database; selecting newer source paths does
not publish those declarations into the fixture.

**RESTART NEEDED.** The orchestrator owns default replacement and the published
fixture/cold gate. This lane has neither restarted default nor replaced that
fixture, and continues the remaining namespace requests without claiming that
missing evidence is green.

- `aba6d445e`: accepted fails-before regression, unchanged. The prescribed ten-
  namespace HEAD load exited 0 immediately after this commit.
- `19827d2b0`: Slice A (required HEAD load exited 0): `src/seon/turn.clj`, `src/seon/cluster/reply.clj`,
  `src/seon/cluster/agent.clj`, `src/seon/context.clj`, `src/seon/fn.clj`,
  `resources/seon/schemas/seon.fn.edn`, and the cluster turn/reply tests.
  Evaluation and context facts no longer project the retired attribute. Reply
  and phase errors no longer construct it. Settlement consumes declared writer
  observations; the general `settlement-refused?` predicate and every caller
  are removed together. Function analysis declares its namespace refusal with
  the actual source as required evidence.

## Live read-only probes

Default was pid 56288, process start 2026-09-22T00:33:08.873Z, prepl 57524.
MCP `runtime_status` and `eval_clj` were available. All forms below used explicit
root `/Users/sean/src/seon`, cluster `default`, mode `jvm`, `read_only true`.
No reset, restart, refork, paid provider call, or durable probe write occurred.

```clojure
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      reply (seon.cluster.reply/sources "; prose only" 'user 100)]
  {:kind-installed? (contains? (:schema db) :seon.error/kind)
   :reply reply})
```

5 ms. `:kind-installed? false`; reply carries
`:seon.cluster.reply/no-forms true`, `:seon.error/kind :seon.cluster.reply/no-forms`,
and message `"Your reply had no form; only comments/prose. Send a form."`.

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      result (seon.db/pull database [:seon.error/kind] [:seon.agent/id "root"])]
  {:result result
   :unguarded-identity-continuation
   (when-let [agent-id (:seon.agent/id result)] (str "Agent     " agent-id))})
```

4 ms. Result carries `:seon.db/invalid-read true` and reports the undeclared
attribute at `(resolve-datom db 37201 :seon.error/kind nil nil)`.
`:unguarded-identity-continuation nil`. This verifies why the whoami branch is a
real domain decision: continuing to construct identity text loses the refusal.
The accepted ruling is to read the declared `:seon.db/invalid-read` member.

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      before (seon.db/basis-t database)]
  (try
    (datahike.core/with database
      [[:db/add -1 :seon.error/kind :seon.cluster.reply/no-forms]])
    (catch clojure.lang.ExceptionInfo failure
      {:basis-before before
       :basis-after (seon.db/basis-t
                      (seon.db/db (seon.cluster.boot/connection "default")))
       :message (ex-message failure)
       :data (ex-data failure)})))
```

3 ms. Basis before and after both 536870954. Message:
`Bad entity attribute :seon.error/kind at [:db/add 37750 :seon.error/kind :seon.cluster.reply/no-forms], not defined in current schema`.
Exception data includes `:error :transact/schema` and `:attribute :seon.error/kind`.
This is the dependency's immutable transaction path, not a live committed turn.

## Fails-before regression

Command, production source unchanged:

```sh
bin/test-fast --paths test/seon/cluster/turn_test.clj -- seon.cluster.turn-test
```

HEAD at admission: `826fdbd6d4fbfaaaa9f3911571ccaa89e24036b4`.
The new regression began at 2026-09-22T03:45:14.451244Z and ended at
03:45:18.494329Z. It failed at `turn_test.clj:1853`:

```text
Bad entity attribute :seon.error/kind at [:db/add 37213 :seon.error/kind :seon.cluster.reply/no-forms], not defined in current schema
expected: error text includes "no form"
actual: error text contains the writer refusal above
```

The fallback created an evaluation, but recorded the writer refusal instead of
the intended no-forms diagnostic. The new test correctly refuses that outcome.
The canonical fixture also verified the retired attribute is absent from its
installed schema. Other namespace tests reported contract and duration failures;
this run is not green. It exited 1: **60 executed, 408 assertions, 88 failures,
17 errors**, recorded run `876775d57511` (basis 536870926). The new no-forms
regression contributed the specific failure shown above.

## Publication timing

| Edit | Edited | Converged | Seconds | Evidence |
| --- | --- | --- | ---: | --- |
| Add no-forms regression | 2026-09-22T03:43:53.735940Z | 2026-09-22T03:44:01.066099Z | 7.330159 | Publication `83f385e9-e3db-4801-b474-a935f740f592`, adopted commit `6ab1f97e-d8a9-5790-98c0-b0015a1df441` on default; hook SOURCE_EDIT / SOURCE_BATCH rows |

| Settlement projection, reply and whoami | 2026-09-22T03:55:53.154550Z | 2026-09-22T03:56:21.723561Z | 28.569011 | `8462335d-0a23-4dd0-9dce-428090332992`, SOURCE_BATCH, adopted `6ab1fc5c-3f71-5005-9bb7-5f4a61909503` |
| Phase and settlement consumers | 2026-09-22T03:58:53.306006Z | 2026-09-22T03:59:26.419480Z | 33.113474 | `104c345b-62b0-4bfe-a16e-54ed65685a97`, SOURCE_BATCH, adopted `6ab1fd10-810f-509b-9405-7eae2d920f77` |
| Fault regression assertion | 2026-09-22T04:09:47.702409Z | 2026-09-22T04:09:53.848211Z | 6.145802 | `12471f1f-1644-4e70-87ba-a2babedddee9`, adopted `6ab1ff90-473d-5312-b2e8-651e59c7c701` |

The two SOURCE_BATCH durations over ten seconds are findings, not acceptable
latency claims. Hook feedback also refused an intermediate undeclared reader
schema and a boolean-only error facet. Both were corrected before the final
run: the reader event uses an explicit map, and namespace refusal requires its
actual source. Publication `7673ad24-2a21-4472-a828-a4a68de53fd2` then converged
in approximately 33.575 seconds (SOURCE_BATCH). Final tested source adoption:
`1e5329be-df1b-4eb6-b681-53d4091d90ae`, commit
`6ab1ffff-dd5c-5636-af4a-c31f55da03bf`. Adoption is not a live turn proof.

## Domain decisions and contracts

This first table explains the Slice A decisions. The current per-site appendix
below enumerates retained member conditions throughout the converted source owners.

| Site / function | Producer | Declared distinguishing member | Why continuation differs |
| --- | --- | --- | --- |
| cluster.agent/whoami | db/pull | `:seon.db/invalid-read` | Refusal must not become identity text. |
| turn/planned-sources; call-turn reply preparation | reply/sources | `:seon.cluster.reply/no-forms` | Preserve reader refusal for ordinary evaluation settlement. |
| fn/analyze-forms; turn/gate-function-install; resume-turn analysis | namespace resolution / analyze-forms | `:seon.fn/namespace-unresolvable` | Analysis cannot index forms with an unresolved namespace; schema requires source evidence. |
| turn/settle-batch!; settle! | phase-wrapped db/transact! | `:seon.turn.loop/phase-failed`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | Refusal requires a terminal refusal transaction instead of successful installation. |
| turn/settle-batch-refusal! | db/transact! | Same three database members | Refusal of the fallback transaction must surface. |
| turn/settle! preparation | phase / explicit failure argument | `:seon.turn.loop/phase-failed`; explicit failure input | Prepare fault recording instead of ordinary receipt data. |
| turn/record-attempt!; call-turn freeze, capture, attempt recording, close | db/transact! | Same three database members | Do not advance provider/turn state without durable facts. |
| turn/attempt-evidence | ai/complete | `:seon.ai/interrupted-text-count` | Preserve stream truncation evidence. |
| turn/call-turn prompt rendering | prompt renderer through phase | `:seon.turn.loop/phase-failed`; prompt `missing-config`, `missing-cluster`, `refused`, `no-trigger`, `budget-exceeded`; render `refused-member`, `candidates`, `invalid-output`; render.unknown `reason`; render.transcript `refused-member`; render.web `refused-member`, `function-unavailable`; config `error-key`, `missing-effective`; db `invalid-read`; schema `expected-value`; turn `missing-opening-datom` | Capture refusal against its immutable database and settle it before provider work. All names are fully qualified in source. |
| turn/call-turn prompt database | opening snapshot / database read | `:seon.turn/missing-opening-datom`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | A refused snapshot cannot serve as capture database. |
| turn/call-turn provider completion | ai/complete | AI members `unreadable-response-member`, `unanswered-reasoning-count`, `exhausted-finish-reason`, `interrupted-text-count`, `provider-error`, `timeout`, `transport-failure`, `extra-body-edn`, `protected-keys`, `missing-credential-variable` | Failure follows the existing attempt disposition; success freezes reply. |
| turn/resume-turn fork, trigger, evaluation, problem | phase | `:seon.turn.loop/phase-failed` | Failed phase cannot provide successful fork/evaluation/problem inputs. |
| turn/resume-turn settlement | settle-batch! | `:refused-outcome` | Use the already declared returned refusal path; no second classification. |
| turn/generate-turn phase | phase | `:seon.turn.loop/phase-failed` | Required caller conversion for the changed phase producer; remaining legacy branch belongs to Slice B. |

`analyze-forms` output is accreted with the new specific namespace refusal schema.
The phase wrapper's polymorphic contract explicitly preserves any supplied
callback value, including flat base errors, and declares its own thrown-failure
translation. Added complete private contracts cover gate-function-install,
settle-batch!, refusal-terminal-data, settle-batch-refusal!, attempt-evidence,
record-attempt!, call-turn, resume-turn, generate-turn, and context/contribution-row.
No new general error predicate or propagation mechanism was added.

## Slice A tests and baseline failure classes

Reply baseline command:
`bin/test-fast --paths test/seon/cluster/turn_test.clj -- seon.cluster.reply-test`.
Run `d38c9d20479b`: 18 tests, 124 assertions, zero failures/errors.

Final Slice A command:

```sh
bin/test-fast --paths src/seon/turn.clj src/seon/cluster/reply.clj src/seon/cluster/agent.clj src/seon/context.clj src/seon/fn.clj resources/seon/schemas/seon.fn.edn test/seon/cluster/turn_test.clj test/seon/cluster/reply_test.clj -- seon.cluster.turn-test seon.cluster.reply-test
```

No-forms regression passed, 04:13:01.538872–04:13:05.444391Z. The existing
prompt-refusal regression now also asserts that the fault's occurrence carries
the actual diagnostic, with the retired attribute absent from fixture schema;
it passed 04:12:58.376658–04:13:01.538686Z. Both use virtual providers, real SCI,
armed contracts and the canonical fixture. Final execution tallies: turn 60 tests, 421 assertions, 84 failures, 16 errors;
reply 18 tests, 124 assertions, zero failures/errors. Aggregate 78 tests,
545 assertions, 84 failures, 16 errors; exit 1. Compared with baseline turn
60/408/88/17 and reply 18/124/0/0. The recorder again refused the immutable
`:seon.test/report-conflict` at moved assertion line 2646 in
`a-refused-delivery-becomes-a-durable-error-fact`. These are observed execution
tallies, not a successfully recorded run. Log: `tmp/b3-slice-a-final.log`.
The launcher and owned test JVM exited before commit; its snapshot was removed.

The final errors remain in legacy fixtures/installation, missing settlement
ordinal, shown-value facets, generated huge-array failure, and contract shapes.
Two error sites not present in the original error list are import-addition and
refused-runtime-schema-registration; baseline assertions in those areas were
already red. Their equivalence has not been proved; no green or no-regression
claim is made for the namespace as a whole.

The original 105 reports (88 failures, 17 errors) were recovered read-only from
recorded run `876775d57511` on current-source, not inferred from later failures.
Its error classes were: invalid old virtual AI errors missing base `at` (four);
undeclared SCI/base error facets in shown-value (two); string function identity
instead of symbol in fixture write (one) and tests-reaching (one); invalid turn
output base (two); unowned fixture component (one); unavailable authored refer
(one); generated-phase huge-array failure (one); missing shown text (one);
refused terminal fixture seed (one); missing rendered-context on prompt capture
(one); missing settlement ordinal (one). Assertions additionally include duration
bounds, program/schema installation, aliases/refers/private context, assignment,
read projections, and the accepted no-forms writer refusal. These baseline
classes are not repaired merely to obtain a green namespace tally.

An intermediate run completed 78 tests, 434 assertions, 68 failures, 32 errors;
its no-forms regression passed. It is superseded by the final run because its
intermediate schema/phase contract defects were fixed. Its recorder refused
`:seon.test/report-conflict`: `complete-members` compares the full immutable
report at runner.clj:2830, including line metadata, against the same captured
failure identity. The failure report's moved source line conflicted. No recorder
or other lane's session was changed. This run is not recorded green evidence.

## Verification boundary and remaining work

**RESTART NEEDED:** the orchestrator must restart default after Slice A and
prove a no-forms reply settles live. This lane does not stop or reset default.
Fixture success does not prove the parked live graph recovered.

Slices B/C now have zero kind/class scan results. Class metadata and its obsolete
schema test are removed; seven remaining debt comments name earlier generic
contracts explicitly. The final namespace matrix is being executed below.
Foreign documents and every pre-existing untracked file are preserved. Earlier
foreign test-selection edits were excluded from the baseline snapshot and have
since landed independently.

## Counts before / after

Counts are matching lines, as `rg -c` reports. Before is the parent of the
regression commit; after is the complete working cut. The parity EDN contains
many properties on one line, so its line count is one rather than occurrence count.

| File | Kind before | Class before | Debt before | Kind after | Class after | Debt after |
|---|---:|---:|---:|---:|---:|---:|
| `bin/seon-hook` | 4 | 0 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/my.fs.edn` | 0 | 16 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/my.message.edn` | 0 | 6 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/my.note.edn` | 0 | 5 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/my.turn.edn` | 0 | 3 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/my.web.edn` | 0 | 11 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.agent.edn` | 0 | 6 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.artifact.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.boot.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.export.edn` | 0 | 5 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.prompt.edn` | 0 | 5 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.registry.edn` | 0 | 8 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.reply.edn` | 0 | 3 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.source.edn` | 0 | 7 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.store.edn` | 0 | 6 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.wake.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.db.edn` | 0 | 4 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.dev.mcp.artifact.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.dev.mcp.edn` | 0 | 3 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.eval.drive.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.fn.binding.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.fn.edn` | 0 | 13 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.message.edn` | 0 | 5 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.print.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.reconcile.edn` | 0 | 6 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.schema.datahike.edn` | 0 | 10 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.schema.edn` | 0 | 25 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.schema.edn.edn` | 0 | 8 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.schema.shape.edn` | 0 | 3 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.search.edn` | 0 | 2 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.turn.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/seon.turn.loop.edn` | 0 | 4 | 0 | 0 | 0 | 0 |
| `script/seon/dev/changed_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `script/seon/dev/dependency_digest.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `script/seon/dev/issues.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `script/seon/dev/mcp.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/my/program.clj` | 0 | 0 | 27 | 0 | 0 | 0 |
| `src/seon/agent.clj` | 11 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/artifact.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/call_preparation.clj` | 0 | 0 | 12 | 0 | 0 | 1 |
| `src/seon/cluster.clj` | 23 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/agent.clj` | 17 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/boot.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/export.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/message.clj` | 17 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/prompt.clj` | 10 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/registry.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/reply.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/source.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/store.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/wake.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/context.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/db.clj` | 24 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/edit/jvm.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/effect.clj` | 0 | 0 | 2 | 0 | 0 | 0 |
| `src/seon/eval.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/eval/drive.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn.clj` | 25 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn/analyzer.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn/schema_shape.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn/signature.cljc` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fs.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fs/jvm.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/issue/detect.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/issue/opening.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/note.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/plan.clj` | 0 | 0 | 38 | 0 | 0 | 0 |
| `src/seon/print.cljc` | 3 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/program.cljc` | 0 | 0 | 2 | 0 | 0 | 0 |
| `src/seon/reconcile.cljc` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/render.clj` | 0 | 0 | 6 | 0 | 0 | 1 |
| `src/seon/render/data.clj` | 0 | 0 | 5 | 0 | 0 | 0 |
| `src/seon/render/transcript.clj` | 0 | 0 | 35 | 0 | 0 | 1 |
| `src/seon/render/walk.clj` | 0 | 0 | 5 | 0 | 0 | 0 |
| `src/seon/render/web.clj` | 0 | 0 | 20 | 0 | 0 | 3 |
| `src/seon/repl.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/run.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema.clj` | 29 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema/datahike.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema/edn.clj` | 12 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema/internal.cljc` | 4 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/sci/eval.clj` | 0 | 0 | 13 | 0 | 0 | 0 |
| `src/seon/search.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/test/accretion.clj` | 0 | 0 | 1 | 0 | 0 | 0 |
| `src/seon/test/runner.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/turn.clj` | 56 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/adoption_diagnostic_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/adoption_margin_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/agent_call_edges_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/agent_situation_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/bounded_boundary_census_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/classification_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_arming_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_identity_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_namespace_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_test.clj` | 10 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/armed_test.clj` | 7 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/evaluate_sources_test.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/instruction_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/mcp_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/message_assignment_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/message_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/problem_routing_test.clj` | 10 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/program_restart_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/prompt_test.clj` | 7 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/registry_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/reply_test.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/resume_artifact_routing_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/source_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/status_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/store_transact_test.clj` | 7 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/turn_test.clj` | 35 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/wake_test.clj` | 7 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster_test.clj` | 7 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_independence_test.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_streams_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_fixture.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_install_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_plan_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/custody_stability_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/data_shapes_test.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db/declaration_population_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/db_test.clj` | 26 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/changed_test_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/dependency_cache_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/dev/edit_feedback_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/edit/jvm_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn/publication_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fn_test.clj` | 25 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/fs/jvm_test.clj` | 9 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/help_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/help_trial_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/html_views_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/loop_proof_test.clj` | 12 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/mcp_test.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/no_provider_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/owned_value_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/public_contract_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/read_evidence_test.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/reconcile_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/refusal_grammar_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/registry_isolation_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render/hiccup_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render_coverage_test.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render_simplification_test.clj` | 8 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/render_source_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_grammar_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_parity_test.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/repl_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/rereads_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/reset_edges_test.clj` | 8 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/returned_error_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run4_install_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run4_reader_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/run6_stall_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/admission_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/datahike_parity.edn` | 0 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema/datahike_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema/declaration_population_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/edn_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema/program_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/schema_test.clj` | 1 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/schema_usage_guard_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/sci/supplied_database_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/source_reconciliation_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/supplied_documentation_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_failure_facts_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_preparation_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_provenance_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_reaching_test.clj` | 10 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_failure_fixture.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_integration_test.clj` | 3 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_test.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_support.clj` | 7 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/test_support_test.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/transact_feedback_test.clj` | 4 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/transaction_result_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/turn_continue_test.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/turn_loop_test.clj` | 19 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/turn_test.clj` | 23 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/turn_work_test.clj` | 1 | 0 | 0 | 0 | 0 | 0 |

## Schema and analysis constructor group

Removed all kind construction members in schema, schema.edn, schema.internal,
schema.datahike, fn.analyzer, fn.schema-shape and fn.signature. Existing owned
refusal members remain. No decision branch was introduced. Touched private
functions now declare their argument and result contracts. Reference-cycle
inspection and contract diagnostic construction accept bound declaration values,
including host predicates; requiring serialized Malli definitions there was
falsified by the canonical reference-map test and corrected. Directory resource
inspection accepts the caller's exact external URL string to keep its contract
concrete without introducing a host-object predicate.

Focused command: `bin/test-fast --paths src/seon/schema.clj src/seon/schema/edn.clj src/seon/schema/internal.cljc src/seon/schema/datahike.clj src/seon/fn/analyzer.clj src/seon/fn/schema_shape.clj src/seon/fn/signature.cljc -- seon.schema-test seon.schema.edn-test seon.schema.datahike-test seon.fn.analyzer-test seon.fn.schema-shape-test`.
Execution: 86 tests, 11886 assertions, 31 failures, 2 errors. Per namespace:
schema 39/10/1; edn 18/6/0; datahike 14/15/1; analyzer 8/0/0; shape 7/0/0
(tests/failures/errors). After the bound-declaration correction, the same paths
with `-- seon.schema-test`: 39 tests, 11443 assertions, 9 failures, zero errors.
Both runners exited 1; immutable report recording refused, so neither run is
recorded green. Logs: `tmp/b3-schema-group.log`, `tmp/b3-schema-correction.log`.
Remaining assertions include retired-kind expectations, schema snapshots and
existing render declaration fixtures; these are not certified as baseline-
equivalent. Test-site conversions remain assigned to Slice C.

Publication `71541a29-2d18-4bc8-ba24-034d30cfd75e`:
04:23:29.839560 → 04:24:10.227841Z, **40.388281 s**, SOURCE_BATCH,
adopted `6ab202d9-ae7d-5986-93c0-b2ce04592b0b`.
Correction `448ff696-59ec-4b86-bb68-c5f350b5966b`:
04:25:12.163086 → 04:25:57.423021Z, **45.259935 s**, SOURCE_BATCH,
adopted `6ab20344-9717-54fc-accd-2331b3054ae1`. Both exceed ten seconds.

## Database and function group

All 24 database and remaining 23 function kind sites are removed. Diagnostic
constructors keep their declared base shape; no new database error taxonomy was
introduced. An attempted addition of six database facets was falsified by schema
publication and canonical fixture acquisition and removed completely before this
commit. `resources/seon/schemas/seon.db.edn` is unchanged.

Retained decisions in this group:

| Owner / decision | Declared member | Why the branch remains |
| --- | --- | --- |
| `db/declared-arity-bounds` | `db/invalid-read`, `schema/expected-value` | Reduce successful declaration rows into arity bounds. |
| `fn/analyze-form` | `fn/namespace-unresolvable` | Select the analyzed form only after namespace resolution. |
| `fn/lint-rows` | `program/declarations-examined` | Resolve and lint a found declaration; retain declaration lookup evidence otherwise. |
| `fn/unresolved-callers`, three reads | `db/invalid-read`, `schema/expected-value` | Assemble unresolved-call evidence from successful query rows. |
| `fn/contract-findings`, three reads | `db/invalid-read`, `schema/expected-value` | Rank contract findings from successful query rows. |
| `fn/gate-set-in`, `fn/gate-sets-in` (acquisition and reduction), `fn/gate-set` | `db/invalid-read`, `schema/expected-value` | Walk successful dependency edges and select each seed's tests. |

Contracts accreted: `analyze-form` admits its namespace-resolution error;
`unresolved-callers` and `contract-findings` admit database invalid reads and
schema validation refusals. `declared-reference-edges`, `gate-set-in`,
`gate-sets-in`, both `gate-sets` arities, `gate-set`, and `tests-reaching` admit
schema validation refusal, which `db/q` can already return. The indexer's
`index-tempids` and `compile-index-transaction` accept partial row maps: their
incremental caller supplies those, not complete program rows.

`bin/test-fast --paths src/seon/db.clj src/seon/fn.clj -- seon.db-test seon.fn-test`:
129 tests, 942 assertions, 60 failures, 5 errors; run `1f9febd4f539`, log
`tmp/b3-db-fn-final.log`. Follow-up with the same paths and `-- seon.fn-test`:
66 tests, 458 assertions, 26 failures, 5 errors (`tmp/b3-fn-union.log`). Both
executions exited 1; recording refused `:seon.test/report-conflict`. The five
error classes were undeclared validation refusal in reference-edge selection,
nil Malli FunctionSchema in contract findings, missing issue-citation file in
an index fixture, prepared-arity fixture refusal, and a missing turn-work
situation in a legacy turn fixture. The reference-edge error still appeared
after its authored union was widened; this is unresolved evidence, not a pass.
The final two forwarding contracts were widened after that run and await the
final matrix. Legacy kind assertions remain assigned to Slice C.

Publication `e0471470-16a0-41e4-957b-48151213efc3` took **36.044719 s**
(04:26:09.494427–04:26:45.539146Z), SOURCE_BATCH. Adding the initially too-narrow
index contract caused subsequent publication refusal. Read-only probe:
`(let [v (ns-resolve 'seon.fn 'compile-index-transaction)] {:contract (:malli/schema (meta v)) :wrapped-contract (:malli/schema (meta (var-get v)))})`
returned the old `:seon.program/rows` argument and nil wrapped metadata in 4 ms.
`(do (require 'seon.fn :reload) {:contract (:malli/schema (meta (ns-resolve 'seon.fn 'compile-index-transaction)))})`
returned the corrected `[:vector :map]` argument in 203 ms. This was reload,
not adoption. Publication subsequently converged at
`6ab2067c-70dc-5121-b0e5-429ca67d9622`; removing the facet experiment converged
at `6ab206c4-9bac-56ae-8edb-8b3ce75d078e`. A second reload probe,
`(do (require 'seon.db :reload) {:constructor-contract (:malli/schema (meta (ns-resolve 'seon.db 'invalid-write))) :index-contract (:malli/schema (meta (ns-resolve 'seon.fn 'compile-index-transaction)))})`,
confirmed the base diagnostic output and partial-row input in 185 ms.
Publication `9c72d26e-a35b-40c6-a6bd-20cef326cf15` converged at
`6ab208ca-b8fa-5aab-b55b-56fac64fee58`.

## Store, publication and operations group

Construction keys were removed from artifact, registry, store, source, wake,
search and reconciliation errors. Existing exception boundaries remain exception
boundaries; flat refusals remain values. Wake declaration refusals and the search
handle supplier now carry the required base timestamp, layer and operation.

| Owner / retained decision | Declared member | Why the branch remains |
| --- | --- | --- |
| `cluster.boot/boot!`, reachability admission | `error/retryable?`; `datahike.gc-guard/mode` | Refuse unavailable admission before branch creation; distinguish roster contention from a sweep for the diagnostic. |
| `cluster.export/copy-store!`, subprocess catch | `operator.subprocess/phase` | Preserve a deadline refusal instead of attempting export fallback after an uncompleted subprocess. |
| `cluster.source/unresolved-report!` | `db/invalid-read`, `schema/expected-value` | Refuse publication when its unresolved-caller read cannot be verified. |
| `reconcile/reconcile!` | `db.write.attempt/request-id`, `db/invalid-read`, `schema/expected-value` | Construct the convergence report only after the writer accepts the transaction. |

`reconcile!` accretes `db/error-result`. Private `copy-store!` accepts a path
string and constructs its File internally; its production caller and cleanup
test replacement use that same contract. Added complete private contracts for
the touched refusal constructors, registry inventory helpers and wake delivery.
The wake route's exception evidence is polymorphic (`schema/value`); the actual
channel, agent identity, callback and delivery result remain constrained.

Dependency evidence: pinned Datahike `006e634ae955c186619adb5f3868cca29d8c97fb`,
`reference-code/datahike/src/datahike/gc_guard.cljc`, lines 1–94 and 172–258:
`try-reachability-permit!` returns a token or retryable refusal. Roster contention
carries its requested mode; sweep contention does not. Boot supplies store id
and `:roster` once per admission. Its CAS work is store-local, not a program scan.
The dependency's legacy discriminator is outside this cut's requested roots;
the first-party reader no longer uses it.

Initial focused command selected artifact, boot, export, registry, source, store,
wake, search and reconcile source paths and namespaces `seon.cluster.boot-test
seon.cluster.export-test seon.cluster.registry-test seon.cluster.source-test
seon.cluster.store-test seon.cluster.wake-test seon.search-test
seon.reconcile-test`. Run `533dfe54143e`: **86 tests, 508 assertions, 28 failures,
12 errors**, recorded, exit 1 (`tmp/b3-operations.log`). The export cleanup
replacement still expected File (two errors); corrected to the path-string
contract. Wake declaration refusal lacked base fields (one error); corrected.
Other errors were refused synthetic component ownership, missing wake events,
and a search fixture storing a symbol as a string. Remaining failures included
old kind assertions, published scalar-row expectations, missing wake events,
and elapsed bounds. These are recorded failures, not a green or an attribution
to another lane.

Correction command uses those same paths plus `test/seon/cluster/export_test.clj`
and selects `seon.cluster.export-test seon.cluster.wake-test`:
**23 tests, 117 assertions, 14 failures, 8 errors**, recorded, exit 1
(`tmp/b3-operations-correction.log`). All six export tests pass. The wake base-field
contract error is gone; refused fixture ownership and missing wake events remain.
The final search supplier base-field correction awaits the final matrix.

Publication correction `e8c81132-a4c3-4ba2-bbe0-bdf5698a00fe`:
05:16:57.316913–05:17:21.796495Z, **24.479582 s**, SOURCE_BATCH,
adopted `6ab20f55-036f-5d0d-8e8a-b5c253a55ad4`.
Search correction `9c4610ec-6381-4a32-a449-978f5aa58fbf`:
05:17:40.631815–05:17:54.267877Z, **13.636062 s**, SOURCE_BATCH,
adopted `6ab20f7b-f403-590d-ab76-3a98251dc5bb`. Both exceed ten seconds;
the hook exposes the batch phase, not an internal phase breakdown.

## Note, filesystem, editing and rendered values group

The note owner's private general error predicate and every caller are removed.
Filesystem and edit constructors drop the retired key and supply the required
base fields. Print diagnostics retain their text/evidence without the stamp.
Run disposition and absent-issue values carry the base contract.

| Owner / retained decision | Declared member | Why the branch remains |
| --- | --- | --- |
| `note/render-notes-html`, `add-note!` pull, `notes` | `db/invalid-read`, `schema/expected-value` | Render or normalize successful note rows. |
| `note/add-note!`, `forget!` writer | `db.write.attempt/request-id`, `db/invalid-read`, `schema/expected-value` | Read the accepted transaction's before/after database. |
| `fs.jvm/error-value` | Filesystem members `path-refused`, `not-found`, `not-directory`, `read-limit`, `read-failed`, `not-regular-file`, `changed-during-read`, `invalid-utf8-window`, `write-limit`, `write-failed`, `blob-unavailable`, `already-exists`, `stale-digest`, `atomic-write-unsupported`, `glob-failed`, `invalid-glob` | Preserve a filesystem refusal caught by its owner; otherwise construct the operation's fallback refusal. |
| `edit.jvm/filesystem-refusal` | Same filesystem members except glob-only members | Preserve the actual filesystem refusal; retain the existing throw boundary for unrelated host faults. |
| `edit.jvm/edit-error` | `my.fs/stale-digest`, `my.fs/invalid-utf8-window` | Translate the two filesystem observations into the edit operation's corresponding refusal. |
| `edit.jvm/edit*`, read | `my.fs/path-refused`, `not-found`, `read-limit`, `read-failed`, `not-regular-file`, `changed-during-read`, `invalid-utf8-window` | Only transform complete readable source. |
| `edit.jvm/edit*`, transform | `my.edit/parse-byte-count`, `unverified-char-span`, `no-match`, `ambiguous-match` | Only write a verified edit candidate. |
| `edit.jvm/edit*`, write | Read members except UTF-8, plus `my.fs/write-limit`, `write-failed`, `blob-unavailable`, `already-exists`, `stale-digest`, `atomic-write-unsupported` | Construct the edit result only after the fenced write succeeds. |
| `issue.detect/public-without-reaching-test`, input reads and gate sets | `db/invalid-read`, `schema/expected-value` | Derive missing-test subjects only from successful declaration and dependency observations. |

Note output contracts accrete `db/error-result`. Private helpers now have complete
contracts. Filesystem marker subjects and diagnostic evidence are intentionally
polymorphic data; the owning members constrain the resulting failure schemas.
Print `emit` has its five-argument contract; sink operations can return arbitrary
sink values. The existing source-signature parser now reads inline `defmulti`
dispatch function arities, including named `fn`, so publication can join that
contract. Clojure 1.12.5 `core.clj:1739–1805` supplies the dispatch function to
MultiFn; no second signature parser was added. The regression asserts the five
bindings for both qualified and unqualified declaration forms.

Focused initial run `cafdc4b70941` selected these ten changed paths and
`my.note-test seon.fs-test seon.fs.jvm-test seon.edit.jvm-test seon.print-test
seon.fn.publication-signature-test my.turn-test seon.issue.detect-test
seon.issue-test`: **62 tests, 425 assertions, 56 failures, 24 errors**, recorded,
exit 1 (`tmp/b3-values.log`). The new layer fields initially used unqualified
keywords, violating `error/layer`; corrected before commit. Other evidence:
note fixtures omit required message members; issue fixtures store symbols as
strings or use symbols where issue ids require strings. Retired-kind assertions
are still present until Slice C. Print and both signature regressions passed.

Publication `0f37a62e-0350-416b-a719-e330ade5c83f` took **29.028834 s**;
`fb0c4ba6-6268-4d29-b598-7e683bc1f4d3` took **14.620119 s**;
`9f86cf95-d7f7-4de8-8cc3-7d248df79007` took **26.916352 s**.
All converged and all exceeded ten seconds in SOURCE_BATCH. Latest of these
adopted `6ab210b9-59bb-5a4c-bb24-6e373ee96cee`.

Correction run `aae70663847f`: 25 tests, 184 assertions, 21 failures, 20 errors
(`tmp/b3-values-correction.log`). It exposed the new generic factory contracts'
missing base-error admission; their output now enumerates the actual filesystem/edit
alternatives together with `error/base`, and the
stale-source constructor names `my.edit/stale-source-error`. This is the existing
polymorphic diagnostic-constructor boundary, not a general error discriminator.
Final focused rerun of `seon.fs.jvm-test seon.edit.jvm-test my.turn-test`, with
the three respective source paths: run `6dfa3bbb4eb4`, **20 tests, 162 assertions,
18 failures, zero errors**, recorded, exit 1 (`tmp/b3-values-final.log`). The
remaining assertions still expect retired kind values; Slice C converts them.
Publication `5991b53b-b0ab-4268-85de-25194af175b6` converged at
`6ab2113a-2ef7-50f2-b1a0-4bdb0650f0c1`.

### Runtime source cut

The remaining runtime source kind references are removed. Retained decisions:

| Owner / producer | Declared members read | Success computation |
|---|---|---|
| agent identity/archive/settings, eval/of-agent | db/invalid-read, schema/expected-value, db.write.attempt/request-id, config/error-key | identity, settings and write-result projections |
| cluster identity, namespace requirements, configuration and artifact writes | the same DB members; config/error-key; instrument/registration-observation | render identity, assemble configuration, resolve admitted entities |
| cluster.agent status/planned sources/plan | cluster.status/unavailable-observation, cluster.reply/no-forms, my.plan/missing-agent-id | status text, source submission and plan use |
| cluster.message references, inbox, read and send | DB read/write members; the producer's my.message recipient/subject/text refusal members | resolve references, order inbox and transact delivery |
| cluster.prompt settings/calibration/context | cluster.prompt/missing-cluster and missing-config; config/error-key; DB read members; render/refused-member, candidates, invalid-output; render.web/refused-member and function-unavailable; render.unknown/reason; render.transcript/refused-member; turn/error-turn-id and missing-opening-datom | calibrate tokens and construct the prompt |
| turn declared/planned sources | cluster.reply/no-forms; render.walk/missing-lookup; declared render/config/DB refusal members | compile and evaluate generated sources |
| turn generated reads | turn/generated-read-attributes | refuse context regeneration that depends on turn activity |
| turn dispositions | turn/invalid-disposition-source | execute a valid disposition |
| turn compaction | turn/compaction-agent-id | preserve the affected agent identity |
| turn writes and eval.drive inbound receipts | DB write members | inspect transaction reports and receipts |
| repl HTML preparation | render.value/root-description | render a prepared value |
| test.runner recording | source/refused-test-run, test/execution-refusal, test.run/unavailable, db.write.attempt/request-id | retain a recording refusal or accept committed refs |

Contract accretions: `system-turn` includes the no-forms and generated-read errors;
status snapshot/agents include unavailable-observation; new private runtime contracts
enumerate their producing alternatives. `capture-mismatch` declares validation-refusal
and carries expected/refused text. Recording failure declares its actual alternatives,
including the existing test/unknown member for an unrecognized recording result.
Filesystem/edit diagnostic factories enumerate their existing declared alternatives;
they do not use a generic base-only output union.

Focused runtime run (13 namespaces, 236 selected tests) used the 13 runtime source
and resource paths listed by `tmp/b3-runtime.log`. It exited **124** after the
no-forms regression's executor cleanup waited for an outstanding backstop task.
The regression now interrupts its owned executor in `finally` before closing it.
There is no final namespace tally or recorded green for this interrupted run.
Observed failures include existing agent-retraction fixtures, missing terminal events,
old kind assertions, outdated settle/open-turn fixtures, and invalid-schema errors.

The invalid-schema boundary was probed independently on the canonical fixture:
`support/with-database`, `db/carried-projection`, and `malli.core/schema` on authored
contracts with that projection's composite registry. Exact executable form is retained
in `tmp/b3-fixture-contract-probe.clj`, output in its `.log`. Fixture basis was
536870944; `(contains? (:schema database) (keyword "seon.error" "kind"))` was false.
Compilation refused `seon.turn/system-turn` and `generated-read-fault` because
`:seon.turn/generated-read-depends-on-turns-error` was absent, and
`disposition-rule-error` because `:seon.turn/invalid-disposition-error` was absent.
The launcher reported its immutable overlay graph six commits behind HEAD.
Read-only default probes compile the new prompt/declared-sources contracts successfully
against default's current carried projection (4 ms). This does not prove the old
canonical fixture can execute the new schema consumers. The orchestrator owns fixture
publication; no cold gate, reset or default restart was performed here.

Publication observations: `96ea94a1` 24.928730 s; `e0ced6cb` converged at
`6ab21363`; explicit factory alternatives `a53c4827` converged at `6ab21341`;
regression cleanup `e4424da9` converged at `6ab21656`. The timed publication exceeded
10 seconds in SOURCE_BATCH. **RESTART NEEDED remains in force** for the live settle proof.

### Program and SCI debt guards

`my.program`'s 27 retained decisions now read `db/invalid-read` and
`schema/expected-value`; its two transaction decisions also read
`db.write.attempt/request-id`. Each success arm performs a query projection,
retraction, or source installation. Existing exception-to-flat-value ownership
is preserved; no new propagation mechanism was introduced.
`program/overrides` uses the two DB read members before history/query processing.
`effect/request*` uses the writer request member before dispatch or settlement.
`call-preparation` uses DB read members before building supplier indexes, adopting
snapshots and preparing arguments. Its two concatenated-query paths now retain each
refusal before concatenation. `sci.eval` uses DB read members for installation
coverage and documentation, and the writer request member for acquisition recording.
`test.accretion/observation` no longer tests the invocation envelope as an error:
the declared invocation result always carries its evaluation record and optional value.

Private contracts were added for the changed functions in these owners. The
call-preparation hook's callable argument retains its polymorphic callable boundary
using the existing schema/value declaration. `prepared-symbols` now explicitly admits
the DB refusal its acquisition can produce. The one supplier-error-fingerprint debt
comment still names `supplied-database-value` and `supplied-connection`, whose generic
base-error declarations require B3 commits 1–3.

Focused program/effect/SCI selection: 122 tests selected; exit 124 at
`seon.effect-test/request-commits-before-io-dispatch-and-settles-once` after the
runner's 320-second no-progress bound. No final tally or green record exists.
`my.program-test` completed with two duration failures (6.668 s and 8.511 s over
five seconds), no reported assertion/exception failure. Before interruption, effect
failures included required config missing from a fixture, a diagnostic-text assertion,
the canonical-facet inventory comparison, and duration bounds.

Focused remaining selection `seon.call-preparation-test seon.sci.eval-test
seon.test.accretion-test`: run **edc124c7a7d2**, **100 tests, 509 assertions,
15 failures, 13 errors**, recorded exit 1 (`tmp/b3-debt-sci.log`). Errors name a nil
Malli registry, unresolved fixture SCI symbols, loaded-contract compilation,
source/contract arity mismatch, auto-check fixture admission and a string where
the declared function symbol is required. This is not passing evidence.

Publication `393080e2` converged at `6ab216d5`; the coordinated program/effect/
call-preparation/SCI/accretion batch `708727db` converged at `6ab216ee`.
The prescribed runtime-commit load for `f8861b66e` exited zero.

### Slice C: plan and rendering decisions

The plan and rendering owners now distinguish the actual DB read/write, plan,
configuration, prompt, await, reply and turn refusals at their producing boundary.
No general predicate or propagation mechanism was added. `history` now admits the
agent-not-found alternative; `render-source-call` admits reply/no-forms; the web
context-error union admits its reachable turn/reply/walk/agent alternatives.
The new private `acquire-entity` contract initially described one read-evidence
record instead of the returned vector. The focused run falsified that declaration;
it was corrected to `[:vector :seon.db/read-evidence]` and the run repeated.

Focused requests (same seven source/resource paths, one JVM at a time):
`my.plan-test seon.render.data-test seon.render.walk-test
seon.render.transcript-test seon.render.web-test`.
Before correction: **108 tests, 646 assertions, 61 failures, 28 errors**.
After correction: **108 tests, 771 assertions, 57 failures, 15 errors**.
The second run's recorder refused with “Snapshot results were not recorded”; this
is an executed tally, not recorded green. Remaining classes include old plan
member/diagnostic expectations, native query bounds, carried-projection identity,
missing Malli schema in the transcript fixture, missing web terminal paint events,
attribute-description registry shape and absent history ordering positions.
No browser-paint or live default settle proof is claimed. **RESTART NEEDED.**

The filesystem/edit/error request executed and recorded **61 tests, 450 assertions,
4 failures, 2 errors**. Filesystem and edit namespaces passed; errors were in
`seon.error-test` (contract-violation and component/facet persistence cases).

Retained decisions in this source group (line numbers at conversion):

| Site | Function / value | Declared member(s) | Why retained |
|---|---|---|---|
| `src/seon/plan.clj:94` | `flat-refusal` / `data` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-item-id`, `:my.plan/unowned-item-id`, `:my.plan/existing-item-id`, `:my.plan/foreign-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/cycle-item-id`, `:my.plan/missing-dependency-id`, `:my.plan/unusable-current-id`, `:my.plan/missing-reference-member`, `:my.plan/unowned-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/failed-query-item-id`, `:my.plan/unsatisfied-query-item-id`, `:seon.plan/non-test-entity` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:104` | `read-result!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:146` | `ref-eid` / `entity` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:177` | `resolve-subject!` / `subject` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:211` | `owned-ids` / `ids` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:250` | `foreign-open-work` / `entity` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:256` | `foreign-open-work` / `row` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:281` | `derived-frontier` / `foreign` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:373` | `agent-plan-pull` / `row` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:390` | `plan` / `agent-entity` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:414` | `plan` / `pulled` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:423` | `plan` / `%` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:455` | `item` / `entity` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:483` | `item` / `agent-id` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:489` | `item` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:498` | `item` / `pulled` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:513` | `items` / `step` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id`, `:my.plan/missing-item-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:524` | `step-summary` / `step` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id`, `:my.plan/missing-item-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:543` | `current` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:558` | `blocked` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:569` | `steps` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:581` | `ready` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:596` | `ready-subjects` / `plan-steps` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:802` | `stale-issue-tests` / `tests` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:813` | `stale-issue-tests` / `stale` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, `:seon.test/selection-refusal`, `:seon.test/admission-refusal`, `:seon.test/execution-refusal`, `:seon.test/unknown`, `:seon.test/resolution-refusal`, `:seon.test/not-runnable`, `:seon.test.run/unavailable`, `:seon.test.run/immutable`, `:seon.instrument/fn` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:894` | `run-issue-tests!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, `:seon.test/selection-refusal`, `:seon.test/admission-refusal`, `:seon.test/execution-refusal`, `:seon.test/unknown`, `:seon.test/resolution-refusal`, `:seon.test/not-runnable`, `:seon.test.run/unavailable`, `:seon.test.run/immutable`, `:seon.instrument/fn` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:912` | `done-query-result` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1056` | `changed-item` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1076` | `add!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1094` | `complete!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1151` | `start!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1192` | `update!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1604` | `plan!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1830` | `render-plan-ai` / `derivation` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1865` | `render-plan-html` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1580` | `format-item-ai` / `step` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-item-id`, `:my.plan/unowned-item-id`, `:my.plan/existing-item-id`, `:my.plan/foreign-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/cycle-item-id`, `:my.plan/missing-dependency-id`, `:my.plan/unusable-current-id`, `:my.plan/missing-reference-member`, `:my.plan/unowned-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/failed-query-item-id`, `:my.plan/unsatisfied-query-item-id`, `:seon.plan/non-test-entity` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1635` | `format-ready-items-ai` / `plan-steps` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-item-id`, `:my.plan/unowned-item-id`, `:my.plan/existing-item-id`, `:my.plan/foreign-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/cycle-item-id`, `:my.plan/missing-dependency-id`, `:my.plan/unusable-current-id`, `:my.plan/missing-reference-member`, `:my.plan/unowned-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/failed-query-item-id`, `:my.plan/unsatisfied-query-item-id`, `:seon.plan/non-test-entity` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/plan.clj:1695` | `format-plan-ai` / `view` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-item-id`, `:my.plan/unowned-item-id`, `:my.plan/existing-item-id`, `:my.plan/foreign-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/cycle-item-id`, `:my.plan/missing-dependency-id`, `:my.plan/unusable-current-id`, `:my.plan/missing-reference-member`, `:my.plan/unowned-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/failed-query-item-id`, `:my.plan/unsatisfied-query-item-id`, `:seon.plan/non-test-entity` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/data.clj:105` | `pull-at` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/data.clj:153` | `outgoing-page` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/data.clj:182` | `incoming-page` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/data.clj:215` | `entity-observation` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/data.clj:217` | `entity-observation` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/walk.clj:169` | `entity-lookup` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/walk.clj:421` | `namespace-connections` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/walk.clj:440` | `acquire-entity` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/walk.clj:468` | `acquire-entity` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/walk.clj:996` | `history` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.agent/no-such-agent` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render.clj:75` | `agent-render-profile` / `` | `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render.clj:110` | `request-projection` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render.clj:134` | `request-profile` / `` | `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render.clj:722` | `render-program-evidence` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render.clj:1660` | `acquire-context!` / `` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:698` | `selected-run-identities` / `queried` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:699` | `selected-run-identities` / `queried` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:823` | `turn-header` / `row` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:830` | `turn-header` / `evaluations` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:892` | `agent-config` / `cluster-name` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:894` | `agent-config` / `effective` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.config/missing-effective` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:924` | `agent-history` / `rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:933` | `agent-history` / `row` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:977` | `format-history-ai` / `derived` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1154` | `turn-rows` / `rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1363` | `render-session-loading` / `rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1433` | `render-session` / `acquired` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1547` | `outline-calibration` / `fitted` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1616` | `outline-everything` / `composed` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check`, `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1622` | `outline-everything` / `composed` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check`, `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1668` | `render-outline` / `rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1672` | `render-outline` / `acquired` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1723` | `ledger-evaluations` / `acquired` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1781` | `ledger-effects` / `%` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1801` | `turn-effects` / `effects` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1816` | `ledger-rows` / `rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1914` | `ledger-turn-body` / `calibration` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:1955` | `render-ledger-turn` / `acquired` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2048` | `fault-problems` / `messages` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2068` | `render-captured-prefix` / `opening` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2132` | `session-budget` / `basis` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2140` | `session-budget` / `plan` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:my.plan/missing-agent-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2176` | `session-problems` / `refreshes` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2178` | `session-problems` / `refreshes` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2338` | `render-ledger` / `acquired` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2342` | `render-ledger` / `rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2342` | `render-ledger` / `evaluations` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2348` | `render-ledger` / `evaluations` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2351` | `render-ledger` / `evaluations` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/transcript.clj:2396` | `render-runtime-html` / `row` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.render.transcript/refused-member` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:924` | `graph-model` / `observation` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.render.data/refused-member` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1386` | `debug-found-values-html` / `acquisition` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1475` | `render-source-call` / `evaluated` | `:seon.cluster.reply/no-forms` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1478` | `render-source-call` / `preview` | `:seon.cluster.reply/no-forms`, `:seon.render.web/refused-member` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1497` | `render-source-call` / `preview` | `:seon.cluster.reply/no-forms`, `:seon.render.web/refused-member` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1502` | `render-source-call` / `preview` | `:seon.cluster.reply/no-forms`, `:seon.render.web/refused-member` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1693` | `acquire-debug-data` / `acquisition` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1705` | `acquire-debug-data` / `pulled` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:1743` | `acquire-debug-data` / `related-values` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:2467` | `change-context` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:2519` | `derive-context!` / `entries` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.agent/no-such-agent` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:2746` | `write-package!` / `result` | `:seon.await/elapsed-ms`, `:seon.await/closed-operation` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:2783` | `await-feed-package!` / `result` | `:seon.await/elapsed-ms`, `:seon.await/closed-operation` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:2995` | `inbound` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:3081` | `ensure-namespace-owner!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:3385` | `context-response` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.config/error-key`, `:seon.render/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render.unknown/reason`, `:seon.render.transcript/refused-member`, `:seon.render.web/refused-member`, `:seon.render.web/function-unavailable`, `:seon.turn/rule`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom`, `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.render.walk/missing-lookup`, `:seon.turn/generated-read-attributes`, `:seon.turn/compaction-agent-id`, `:seon.db.write.attempt/request-id`, `:seon.instrument/check` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |
| `src/seon/render/web.clj:3642` | `start!` / `result` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.db.write.attempt/request-id` | Success continues acquiring, transforming or formatting the owning value; refusal preserves the producer’s declared alternative. |

Exactly seven debt comments remain, each at a still-generic producer boundary
owned by B3 commits 1–3. They are not kind readers:

| Site | Callee needing the earlier contract cut | Remaining decision |
|---|---|---|
| `call_preparation.clj:450` | `seon.db/supplied-database-value`, `supplied-connection` | Supplier fingerprint comment; these outputs still include generic error/value. |
| `sci/kernel.clj:584` | `seon.error.refusal/refusal` | Already-flat refusal versus ordinary throwable data; producer admits generic base plus facets. |
| `render.clj:1083` | `seon.sci.kernel/invoke` | Selected renderer result versus valid output; arbitrary admitted renderer value. |
| `render/transcript.clj:1597` | `seon.render/render-call` | Error line versus selected HTML result. |
| `render/web.clj:491` | `seon.render/render-call` | Fleet refusal versus successful fleet content. |
| `render/web.clj:497` | `seon.render/render-call` | Successful fleet content projection. |
| `render/web.clj:1034` | `seon.render/render-call` | Debug refusal HTML versus successful renderer output. |

### Slice C: class metadata, tests and operator callers

Removed every class property from 31 schema resources and removed the obsolete
`test/seon/error_class_schema_test.clj` mechanism test. The two arbitrary-property
schema tests now exercise the already-declared `:seon.config/dial` property.
The current parity baseline was regenerated from packaged declaration facts:
**3251 forms, 1167 native attributes**. The initial historical native oracle was
preserved byte-for-value; historical non-storable class properties were removed.
A canonical fixture carrying older declaration facts still fails this current
baseline comparison; that is recorded as drift, never silently accepted.

Test construction maps drop the retired key. Injected DB, prompt, filesystem and
provider failures that feed retained decisions now carry their producer’s existing
member. Evaluation assertions read durable error text; fault assertions read
occurrence/error facts and their declared evidence. Message model checks compare
actual refusal member maps (limit, unknown recipient), not a replacement enum.
Positive transaction/creation/analysis assertions inspect the declared success
result. Source-keyword indexing now uses a boolean sample refusal member.
The operator hook requires a source commit for publication success; its fallback
refusal has base attribution. Deadline scripts read the declared subprocess phase.

A complete-definition audit found two missed private source contracts,
`db/dependency-error` and `schema.edn/schema-resource-paths`; both are now declared.
All 36 changed test helpers now have complete contracts. Their polymorphic slots
are callback results, arbitrary evaluated values or candidate syntax, using the
existing schema/value declaration; no new predicate or `:any` was introduced.
The existing optional analyzed program-row position and core.async per-port output
remain proven polymorphic boundaries, not blanket nullable application values.

Schema/error/helper/operator run **8a9ff6bfe369** recorded **162 tests, 13174
assertions, 41 failures, 21 errors**. Failure classes: stale predicate/declaration
fixture facts, native parity drift, renderer-codec fixture input contracts,
component/facet persistence, absent dependency-cache private seams, and hook tests
refused while canonicalizing an unnamed callable. A storage assertion was corrected
to distinguish the nilable-schema admission refusal from the native converter’s
attribute refusal; the empty-directory caller now supplies the declared URL string.
These two conversions are included in the subsequent core matrix request.

`683fc9c20` (plan/render decisions) passed the prescribed namespace load, exit 0.

The next core request (`seon.schema.edn-test seon.schema.datahike-test
seon.db-test seon.fn-test seon.cluster.message-test seon.cluster.reply-test`)
executed **199 tests, 2266 assertions, 36 failures, 8 errors**. Its recorder
refused the snapshot record, so this is not recorded green. Reply completed
without reported failures. The final storage assertion correction distinguishes
both producer boundaries: native `schema.datahike/attr` and early
`schema/nilable-value-schema`; a read-only default probe independently verified
the latter in 5 ms.

### Additional publication clocks

These are edit timestamp → SOURCE_BATCH convergence timestamps from the hook’s
result envelopes and `logs/hook-debug.log`, including queue time. No finer internal
phase is inferred. Every row over ten seconds is a finding in the named phase.
These hook clocks do not substitute for the orchestrator’s committed cold
publication measurement script, which this lane did not run.

| Publication | Edit → adoption seconds | Reported phase | Result |
|---|---:|---|---|
| `fac50db2` | 17.092979 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab210cf-c5f4-58c7-a939-21849200c952", :seon.boot/cluster-name "default"} |
| `4f011ea4` | 15.233131 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab211ce-ce37-53df-836c-8bfc3a9a059f", :seon.boot/cluster-name "default"} |
| `a53c4827` | 13.796381 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21341-7fbf-505c-a8e7-e97e04534028", :seon.boot/cluster-name "default"} |
| `e0ced6cb` | 22.421618 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21363-a09e-55e5-be97-5e74c64d8002", :seon.boot/cluster-name "default"} |
| `e4424da9` | 8.446374 | SOURCE_BATCH | converged: {:seon.source/commit-id #uuid "6ab21656-6be1-5b79-9cef-08636d0d7e28", :seon.boot/cluster-name "default"} |
| `393080e2` | 12.127758 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab216d5-8ed5-56de-91ec-49f2137f1989", :seon.boot/cluster-name "default"} |
| `708727db` | 36.521105 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab216ee-b8a9-50ed-8279-35d7c41dfb09", :seon.boot/cluster-name "default"} |
| `bdcd40b0` | 26.517653 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21716-9d45-5150-8a2f-8a6e89c742ed", :seon.boot/cluster-name "default"} |
| `3065670b` | 16.101429 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab2172b-f1a3-5ef6-9d81-67b565fe0fd1", :seon.boot/cluster-name "default"} |
| `5bfb0071` | 25.527757 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21781-8e26-5312-9b2e-56230c240d91", :seon.boot/cluster-name "default"} |
| `f53c35d5` | 32.621550 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab217e0-965b-564a-82e4-5651fb3032f8", :seon.boot/cluster-name "default"} |
| `f8c881e7` | 22.279568 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab217f1-a078-5aec-bcd3-e337a2108386", :seon.boot/cluster-name "default"} |
| `fc9f625d` | 23.247413 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21876-739a-5e7c-8e5c-7312048a71f2", :seon.boot/cluster-name "default"} |
| `572d8d60` | 20.428763 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab2194b-1ea6-5f2b-a480-9ea1597a38e8", :seon.boot/cluster-name "default"} |
| `d4a8724f` | 30.408119 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab219cc-8a0b-53cc-a2c9-2b721271761f", :seon.boot/cluster-name "default"} |
| `d168414d` | 26.849311 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab219ea-0215-5e73-8cb9-3369b00257e6", :seon.boot/cluster-name "default"} |
| `014182bd` | 17.422490 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21a86-011a-5fbd-b86c-b16363f98574", :seon.boot/cluster-name "default"} |
| `4d1d17ff` | 23.614685 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21a95-470e-5f5a-a912-7cc15a6657ba", :seon.boot/cluster-name "default"} |
| `eca5496b` | 24.290490 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21b40-83e6-5c77-bf93-748b6ceb375e", :seon.boot/cluster-name "default"} |
| `3ebfbb94` | 23.756930 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21c49-efd5-5019-ab17-c05d06fbfab8", :seon.boot/cluster-name "default"} |
| `afbe205b` | 15.472285 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21cb8-d4d2-5718-9323-a2bee5bf00e8", :seon.boot/cluster-name "default"} |
| `81c1cc75` | 20.323916 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21d08-91cf-5724-8128-c964ac975035", :seon.boot/cluster-name "default"} |
| `56e0d1a4` | 17.364820 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21dc1-bbc4-54ed-96fc-5895a0f4047d", :seon.boot/cluster-name "default"} |
| `04bfc651` | 44.345978 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21dd8-a08d-5d31-ba84-29960f9a2170", :seon.boot/cluster-name "default"} |
| `b7c7033c` | 16.357791 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21e5d-a50d-5676-9d10-6ddab530faa2", :seon.boot/cluster-name "default"} |
| `2397acd5` | 14.872860 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21eb9-8eb4-512d-8008-ea3d6e1a6489", :seon.boot/cluster-name "default"} |
| `dddbfa84` | 9.910037 | SOURCE_BATCH | converged: {:seon.source/commit-id #uuid "6ab21f21-b441-5213-86ca-8a1f4d108618", :seon.boot/cluster-name "default"} |
| `809e2b8f` | 11.091002 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab21faa-4889-5059-a979-9eaacf372722", :seon.boot/cluster-name "default"} |
| `2282aaa6` | 42.302396 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab2201d-526b-5660-8a43-11866b5cd751", :seon.boot/cluster-name "default"} |
| `aa2a2789` | 27.339202 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab220bf-6249-57a1-90e2-e4be4d6a7c30", :seon.boot/cluster-name "default"} |
| `a95fe029` | 40.822433 | SOURCE_BATCH | >10 s finding; converged: {:seon.source/commit-id #uuid "6ab220d4-af40-56b1-b9ef-be0edaf1b08a", :seon.boot/cluster-name "default"} |
| `f111797c` | 8.107959 | SOURCE_BATCH | converged: {:seon.source/commit-id #uuid "6ab220e1-5614-526f-aa02-5542eb1e24d8", :seon.boot/cluster-name "default"} |
| `dbf8f245` | 7.142301 | SOURCE_BATCH | converged: {:seon.source/commit-id #uuid "6ab22104-004a-5a53-aee4-d197f74e3e90", :seon.boot/cluster-name "default"} |
| `ae3eb1c5` | 7.279059 | SOURCE_BATCH | converged: {:seon.source/commit-id #uuid "6ab221cb-b990-5c2c-9268-3560caa13ed7", :seon.boot/cluster-name "default"} |

### Current retained member-condition inventory

This appendix deliberately includes positive success decisions in the same changed
functions as refusal decisions. Each row is a retained conditional site, not a new
error taxonomy. The named source line supplies the full producer call and success
continuation; the preceding owner sections explain each callee family’s choice.
No unconverted kind caller is hidden by limiting the inventory to a name pattern.

| Current site | Owner | Members read in retained condition | Decision |
|---|---|---|---|
| `src/my/program.clj:60` | `locate` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:89` | `names-through` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:115` | `caller-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:120` | `caller-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:131` | `caller-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:136` | `caller-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:143` | `caller-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:189` | `tests-reaching` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:205` | `key-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:210` | `key-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:219` | `key-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:256` | `render-referrers` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:267` | `proposed-plan` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:309` | `breaks` | `:seon.ns/name` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:312` | `breaks` | `:seon.ns/name` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:315` | `breaks` | `:seon.ns/name` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:319` | `breaks` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:334` | `breaks` | `:seon.ns/name` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:336` | `breaks` | `:seon.schema/key` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:363` | `entity-facts` | `:db.cardinality/many`, `:db/cardinality` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:367` | `entity-facts` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:384` | `history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:388` | `history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:394` | `history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:403` | `history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:409` | `history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:413` | `history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:468` | `overrides` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:544` | `retract-operation!` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:566` | `retract-operation!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:620` | `ns-unalias!` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:639` | `ns-unalias!` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/my/program.clj:643` | `ns-unalias!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:21` | `identity` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:22` | `identity` | `:seon.agent/id` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:42` | `archived?` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:43` | `archived?` | `:seon.agent/id` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:64` | `archive-call` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:66` | `archive-call` | `:db/id` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:69` | `archive-call` | `:seon.agent/archived-tx` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:92` | `update-settings-call` | `:db/id` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:97` | `update-settings-call` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:119` | `settings!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:135` | `effective-settings` | `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/agent.clj:148` | `effective-settings` | `:seon.config.agent/show-all-settings` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:240` | `coherent-supplier` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:314` | `newest-row-transaction` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:418` | `prepared-symbols` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:419` | `prepared-symbols` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:420` | `prepared-symbols` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:444` | `snapshot` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:508` | `snapshot` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:561` | `current-snapshot` | `:seon.call-preparation/checked-through-t` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:565` | `current-snapshot` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:591` | `watch!` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:733` | `supplied-map-entries` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:750` | `supplied-map-entries` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:751` | `supplied-map-entries` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:1410` | `hook` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:1414` | `hook` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:1418` | `hook` | `:seon.call-preparation/prepared-symbols` | `if-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/call_preparation.clj:1425` | `hook` | `:seon.call-preparation/candidate-count`, `:seon.call-preparation/invalid-key`, `:seon.call-preparation/unavailable-key` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:192` | `format-ai` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:194` | `format-ai` | `:seon.cluster/name` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:424` | `mcp-project` | `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:428` | `mcp-project` | `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:432` | `mcp-project` | `:seon.config/error-key` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:435` | `mcp-project` | `:seon.eval/shown` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:507` | `mcp-project` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:590` | `mcp-get-value` | `:seon.boot/cluster-connection` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:592` | `mcp-get-value` | `:seon.dev.mcp.artifact/digest` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:594` | `mcp-get-value` | `:seon.dev.mcp.artifact/digest` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:608` | `mcp-get-value` | `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:611` | `mcp-get-value` | `:seon.render.data/value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:935` | `warn-low-space!` | `:seon.config/on-core-error` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:943` | `warn-low-space!` | `:seon.config/on-core-error` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:1312` | `lookup-resolution` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:1315` | `lookup-resolution` | `:db/id` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:1989` | `namespace-requires` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2073` | `development-source-refresh!` | `:seon.source/commit-id` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2085` | `development-source-refresh!` | `:seon.issue/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2087` | `development-source-refresh!` | `:seon.schema/key` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2141` | `development-source-refresh!` | `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2154` | `development-source-refresh!` | `:seon.config/on-core-error`, `:seon.instrument/instrumented`, `:seon.instrument/registration-observation` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2173` | `development-source-refresh!` | `:seon.render.web/runtime-eval-channel`, `:seon.render.web/view` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:2502` | `ensure-entity!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3078` | `disarm-agents!` | `:seon.render.web/served` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3080` | `disarm-agents!` | `:seon.turn.loop/cluster` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3084` | `disarm-agents!` | `:seon.turn.loop/cluster` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3088` | `disarm-agents!` | `:seon.agent/quiesce` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3095` | `disarm-agents!` | `:seon.agent/quiesced` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3104` | `disarm-agents!` | `:seon.agent/routing` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3108` | `disarm-agents!` | `:seon.flow/graph` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3122` | `disarm-agents!` | `:seon.flow/graph`, `:seon.search/handle` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3125` | `disarm-agents!` | `:seon.flow/error-fanout` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3127` | `disarm-agents!` | `:seon.turn.loop/cluster` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster.clj:3132` | `disarm-agents!` | `:seon.render.web/view` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:146` | `steward-call` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:213` | `whoami` | `:seon.db/invalid-read` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:215` | `whoami` | `:seon.agent/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:263` | `render-identity-html` | `:seon.db/db` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:264` | `render-identity-html` | `:seon.agent/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:268` | `render-identity-html` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:294` | `render-identity-html` | `:seon.db/db` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:296` | `render-identity-html` | `:seon.cluster.status/unavailable-observation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:330` | `render-id-html` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:354` | `render-situation-ai` | `:seon.agent/unread-message-count` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:356` | `render-situation-ai` | `:seon.agent/id`, `:seon.db/db` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:359` | `render-situation-ai` | `:my.plan/missing-agent-id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:366` | `render-situation-ai` | `:seon.agent/unread-message-count` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:370` | `render-situation-ai` | `:seon.turn/trigger` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:417` | `assigned-to` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:581` | `submit-source-in-projection` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:604` | `submit-source-in-projection` | `:seon.cluster.reply/no-forms` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:619` | `submit-source-in-projection` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:625` | `submit-source-in-projection` | `:seon.agent/wake` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:973` | `armer-step` | `::flow/resume` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:977` | `armer-step` | `::flow/stop` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:987` | `armer-step` | `:seon.agent/quiesce` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/agent.clj:1042` | `armer-step` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/boot.clj:133` | `stand-boot-layers!` | `:seon.error/retryable?` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/boot.clj:136` | `stand-boot-layers!` | `:datahike.gc-guard/mode` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/export.clj:214` | `copy-store!` | `:seon.operator.subprocess/phase` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/message.clj:248` | `agent-reference-id` | `:seon.agent/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/message.clj:259` | `agent-reference-id` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/message.clj:322` | `format-ai` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/message.clj:601` | `read` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/message.clj:701` | `send-call` | `:seon.error/values` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:53` | `effective-ai-settings` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:66` | `effective-ai-settings` | `:seon.config/missing-effective` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:97` | `calibration-for` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:139` | `agent-calibration` | `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:366` | `acquire-context-report` | `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.render/refused-member`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:375` | `acquire-context-report` | `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/error-turn-id`, `:seon.turn/missing-opening-datom` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:389` | `acquire-context-report` | `:seon.cluster.prompt/text`, `:seon.render.history/entries` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:407` | `acquire-context-report` | `:seon.turn/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/prompt.clj:433` | `prompt` | `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/cluster/source.clj:195` | `unresolved-report!` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:1444` | `lookup-ref-error` | `:db/unique` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:2255` | `validate-pulled-value` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:2349` | `pull-call` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:3833` | `declared-arity-bounds` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:3932` | `write-render-target-error` | `:seon.fn/sym` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4115` | `write-report-error` | `:seon.fn/sym`, `:seon.schema/form`, `:seon.schema/key` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4122` | `write-report-error` | `:seon.call-preparation/key`, `:seon.fn/sym`, `:seon.schema.shape/fingerprint`, `:seon.schema/key`, `:seon.test/sym` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4127` | `write-report-error` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4220` | `retention-check` | `:seon.db/value-identities`, `:seon.db/values` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4231` | `retention-check` | `:seon.db/active?`, `:seon.db/creator`, `:seon.db/values` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4318` | `transact-call` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4408` | `transact-call` | `:seon.schema/projection` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4424` | `transact-call` | `:datahike/validation-refusal`, `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/db.clj:4431` | `transact-call` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:34` | `edit-error` | `:my.fs/stale-digest` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:37` | `edit-error` | `:my.fs/invalid-utf8-window` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:105` | `filesystem-refusal` | `:my.fs/already-exists`, `:my.fs/atomic-write-unsupported`, `:my.fs/blob-unavailable`, `:my.fs/changed-during-read`, `:my.fs/invalid-utf8-window`, `:my.fs/not-directory`, `:my.fs/not-found`, `:my.fs/not-regular-file`, `:my.fs/path-refused`, `:my.fs/read-failed`, `:my.fs/read-limit`, `:my.fs/stale-digest`, `:my.fs/write-failed`, `:my.fs/write-limit` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:123` | `edit*` | `:my.fs/changed-during-read`, `:my.fs/invalid-utf8-window`, `:my.fs/not-found`, `:my.fs/not-regular-file`, `:my.fs/path-refused`, `:my.fs/read-failed`, `:my.fs/read-limit` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:129` | `edit*` | `:my.edit/expected-digest` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:134` | `edit*` | `:my.edit/ambiguous-match`, `:my.edit/no-match`, `:my.edit/parse-byte-count`, `:my.edit/unverified-char-span` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/edit/jvm.clj:147` | `edit*` | `:my.fs/already-exists`, `:my.fs/atomic-write-unsupported`, `:my.fs/blob-unavailable`, `:my.fs/changed-during-read`, `:my.fs/not-found`, `:my.fs/not-regular-file`, `:my.fs/path-refused`, `:my.fs/read-failed`, `:my.fs/read-limit`, `:my.fs/stale-digest`, `:my.fs/write-failed`, `:my.fs/write-limit` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/effect.clj:903` | `request*` | `:seon.effect/missing-bound`, `:seon.effect/time-limit-attribute` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/effect.clj:972` | `request*` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/effect.clj:976` | `request*` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.effect/transaction`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/effect.clj:1023` | `request*` | `:seon.effect/disposition` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/effect.clj:1033` | `request*` | `:seon.await/elapsed-ms` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/eval.clj:33` | `of-agent` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/eval.clj:35` | `of-agent` | `:seon.agent/id` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/eval.clj:65` | `of-agent` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/eval/drive.clj:99` | `inbound!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:613` | `var-row` | `::analyzer/test` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:969` | `analyze-forms` | `:seon.ns/name` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1038` | `analyze-form` | `:seon.fn/namespace-unresolvable` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1242` | `lint-rows` | `:seon.program/declarations-examined` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1528` | `unresolved-callers` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1529` | `unresolved-callers` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1530` | `unresolved-callers` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1633` | `contract-findings` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1634` | `contract-findings` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1635` | `contract-findings` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:1685` | `contract-findings` | `:seon.fn.contract.finding/unguarded-variadic` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:2189` | `replace-manifest-artifacts` | `:seon.fn.file/relative-path` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:2533` | `rows` | `:seon.fn/roots` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:2931` | `compile-index-transaction` | `:db/id` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3322` | `index!` | `:seon.reconcile/adopt-identities` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3338` | `index!` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3344` | `index!` | `:seon.fn/changed-paths`, `:seon.reconcile/adopt-identities` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3359` | `index!` | `:seon.reconcile/adopt-identities` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3361` | `index!` | `:seon.fn/changed-paths` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3363` | `index!` | `:seon.fn/previous-manifest` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3386` | `index!` | `:seon.source/relative-file-digests` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3407` | `index!` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fn.clj:3419` | `index!` | `:seon.fn/changed-paths`, `:seon.fn/manifest` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/fs/jvm.clj:55` | `error-value` | `:my.fs/already-exists`, `:my.fs/atomic-write-unsupported`, `:my.fs/blob-unavailable`, `:my.fs/changed-during-read`, `:my.fs/glob-failed`, `:my.fs/invalid-glob`, `:my.fs/invalid-utf8-window`, `:my.fs/not-directory`, `:my.fs/not-found`, `:my.fs/not-regular-file`, `:my.fs/path-refused`, `:my.fs/read-failed`, `:my.fs/read-limit`, `:my.fs/stale-digest`, `:my.fs/write-failed`, `:my.fs/write-limit` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/issue/detect.clj:318` | `public-without-reaching-test` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/issue/detect.clj:323` | `public-without-reaching-test` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/issue/opening.clj:198` | `source` | `:seon.test/syms` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/issue/opening.clj:204` | `source` | `:seon.test/syms` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/note.clj:124` | `render-notes-html` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:94` | `flat-refusal` | `:my.plan/cycle-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/existing-item-id`, `:my.plan/failed-query-item-id`, `:my.plan/foreign-item-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-dependency-id`, `:my.plan/missing-item-id`, `:my.plan/missing-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/unowned-item-id`, `:my.plan/unowned-reference-member`, `:my.plan/unsatisfied-query-item-id`, `:my.plan/unusable-current-id`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.plan/non-test-entity`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:102` | `read-result!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:141` | `ref-eid` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:170` | `resolve-subject!` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:201` | `owned-ids` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:237` | `foreign-open-work` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:240` | `foreign-open-work` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:262` | `derived-frontier` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:351` | `agent-plan-pull` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:365` | `plan` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:386` | `plan` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:392` | `plan` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:392` | `plan` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:421` | `item` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:446` | `item` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:449` | `item` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:455` | `item` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:467` | `items` | `:my.plan/missing-agent-id`, `:my.plan/missing-item-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:475` | `step-summary` | `:my.plan/missing-agent-id`, `:my.plan/missing-item-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:491` | `current` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:493` | `current` | `:my.plan.item/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:503` | `blocked` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:511` | `steps` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:520` | `ready` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:532` | `ready-subjects` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:736` | `stale-issue-tests` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:744` | `stale-issue-tests` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/fn`, `:seon.schema/expected-value`, `:seon.test.run/immutable`, `:seon.test.run/unavailable`, `:seon.test/admission-refusal`, `:seon.test/execution-refusal`, `:seon.test/not-runnable`, `:seon.test/resolution-refusal`, `:seon.test/selection-refusal`, `:seon.test/unknown` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:771` | `run-issue-tests!` | `:seon.test.run/unavailable` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:822` | `run-issue-tests!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/fn`, `:seon.schema/expected-value`, `:seon.test.run/immutable`, `:seon.test.run/unavailable`, `:seon.test/admission-refusal`, `:seon.test/execution-refusal`, `:seon.test/not-runnable`, `:seon.test/resolution-refusal`, `:seon.test/selection-refusal`, `:seon.test/unknown` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:838` | `done-query-result` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:979` | `changed-item` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:996` | `add!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1011` | `complete!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1065` | `start!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1103` | `update!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1502` | `plan!` | `:my.plan/converged?` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1512` | `plan!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1580` | `format-item-ai` | `:my.plan/cycle-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/existing-item-id`, `:my.plan/failed-query-item-id`, `:my.plan/foreign-item-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-dependency-id`, `:my.plan/missing-item-id`, `:my.plan/missing-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/unowned-item-id`, `:my.plan/unowned-reference-member`, `:my.plan/unsatisfied-query-item-id`, `:my.plan/unusable-current-id`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.plan/non-test-entity`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1632` | `format-ready-items-ai` | `:my.plan/cycle-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/existing-item-id`, `:my.plan/failed-query-item-id`, `:my.plan/foreign-item-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-dependency-id`, `:my.plan/missing-item-id`, `:my.plan/missing-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/unowned-item-id`, `:my.plan/unowned-reference-member`, `:my.plan/unsatisfied-query-item-id`, `:my.plan/unusable-current-id`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.plan/non-test-entity`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1689` | `format-plan-ai` | `:my.plan/cycle-item-id`, `:my.plan/duplicate-item-id`, `:my.plan/duplicate-sibling-position`, `:my.plan/existing-item-id`, `:my.plan/failed-query-item-id`, `:my.plan/foreign-item-id`, `:my.plan/missing-agent-id`, `:my.plan/missing-dependency-id`, `:my.plan/missing-item-id`, `:my.plan/missing-reference-member`, `:my.plan/missing-subject-attribute`, `:my.plan/unbounded-query-agent`, `:my.plan/unowned-item-id`, `:my.plan/unowned-reference-member`, `:my.plan/unsatisfied-query-item-id`, `:my.plan/unusable-current-id`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.plan/non-test-entity`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1698` | `format-plan-ai` | `:my.plan/objective` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1726` | `render-plan-ai` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1750` | `render-plan-html` | `:db/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/plan.clj:1758` | `render-plan-html` | `:my.plan/missing-agent-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/reconcile.cljc:439` | `reconcile!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:74` | `agent-render-profile` | `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:110` | `request-projection` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:121` | `request-profile` | `:seon.db/db`, `:seon.schema/projection` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:134` | `request-profile` | `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:723` | `render-program-evidence` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:1654` | `acquire-context!` | `:seon.render/context-action` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render.clj:1661` | `acquire-context!` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/data.clj:105` | `pull-at` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/data.clj:108` | `pull-at` | `:seon.render.data/root-description` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/data.clj:153` | `outgoing-page` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/data.clj:182` | `incoming-page` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/data.clj:215` | `entity-observation` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/data.clj:217` | `entity-observation` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:699` | `selected-run-identities` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:700` | `selected-run-identities` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:825` | `turn-header` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:832` | `turn-header` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:835` | `turn-header` | `:seon.turn/reply-size` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:894` | `agent-config` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:897` | `agent-config` | `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:927` | `agent-history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:936` | `agent-history` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:980` | `format-history-ai` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1157` | `turn-rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1366` | `render-session-loading` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1383` | `render-session` | `:seon.turn/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1390` | `render-session` | `:seon.instrument/check`, `:seon.instrument/explanations`, `:seon.instrument/fn` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1421` | `render-session` | `:seon.turn/attempts` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1428` | `render-session` | `:seon.turn/id` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1436` | `render-session` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1475` | `render-session` | `:seon.turn/attempts` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1483` | `render-session` | `:seon.render.history/bytes` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1551` | `outline-calibration` | `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1572` | `outline-unit` | `:seon.eval/origin` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1597` | `outline-unit` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1618` | `outline-everything` | `:seon.instrument/check`, `:seon.render.web/refused-member`, `:seon.render/refused-member` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1621` | `outline-everything` | `:seon.agent/no-such-agent`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1627` | `outline-everything` | `:seon.agent/no-such-agent`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1654` | `render-outline` | `:seon.instrument/check`, `:seon.render.web/refused-member`, `:seon.render/refused-member` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1658` | `render-outline` | `:seon.sci.eval/ctx` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1673` | `render-outline` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1677` | `render-outline` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1728` | `ledger-evaluations` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1786` | `ledger-effects` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1786` | `ledger-effects` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1807` | `turn-effects` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1808` | `turn-effects` | `:seon.turn/closed-tx` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1822` | `ledger-rows` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1921` | `ledger-turn-body` | `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1932` | `ledger-turn-body` | `:seon.cluster.eval/error` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1962` | `render-ledger-turn` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:1964` | `render-ledger-turn` | `:seon.turn/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2056` | `fault-problems` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2076` | `render-captured-prefix` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2141` | `session-budget` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2186` | `session-problems` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2188` | `session-problems` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2348` | `render-ledger` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2352` | `render-ledger` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2358` | `render-ledger` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2361` | `render-ledger` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2398` | `render-runtime-html` | `:seon.render.transcript/refused-member` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2406` | `render-runtime-html` | `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2407` | `render-runtime-html` | `:seon.agent/runtime` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/transcript.clj:2448` | `render-runtime-html` | `:seon.runtime/listens` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:169` | `entity-lookup` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:416` | `namespace-connections` | `:seon.ns/name` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:421` | `namespace-connections` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:438` | `acquire-entity` | `:datahike.pull/plan`, `:seon.db/invalid-read`, `:seon.render.call/output`, `:seon.render.call/read-evidence`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:457` | `acquire-entity` | `:db/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:460` | `acquire-entity` | `:seon.ns/requires` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:465` | `acquire-entity` | `:seon.ns/name` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:469` | `acquire-entity` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:480` | `acquire-entity` | `:seon.render.call/basis-transaction` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:991` | `history` | `:seon.db/pull-selector` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:994` | `history` | `:seon.turn/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:997` | `history` | `:seon.agent/no-such-agent`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:1006` | `history` | `:seon.render.unknown/reason`, `:seon.render/invalid-output`, `:seon.render/refused-member` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/walk.clj:1014` | `history` | `:seon.turn.work/situation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:513` | `page-result` | `:seon.render.fragment/evidence` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:524` | `page-result` | `:seon.render.fragment/evidence` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:925` | `graph-model` | `:seon.db/invalid-read`, `:seon.render.data/refused-member`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1034` | `debug-preview-html` | `:seon.error/at`, `:seon.error/layer`, `:seon.error/operation` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1035` | `debug-preview-html` | `:seon.render/html` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1374` | `debug-found-values-html` | `:db/isComponent` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1388` | `debug-found-values-html` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1404` | `debug-found-values-html` | `:seon.render.data/complete?`, `:seon.render.data/incoming` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1450` | `render-source-call` | `:seon.render.call/output` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1480` | `render-source-call` | `:seon.cluster.reply/no-forms`, `:seon.render.web/refused-member` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1696` | `acquire-debug-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1708` | `acquire-debug-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1742` | `acquire-debug-data` | `:seon.agent/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:1746` | `acquire-debug-data` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:2481` | `derive-context!` | `:seon.render/context-action` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:2522` | `derive-context!` | `:seon.agent/no-such-agent`, `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:2526` | `derive-context!` | `::transcript/ledger?` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:2749` | `write-package!` | `:seon.await/closed-operation`, `:seon.await/elapsed-ms` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:2786` | `await-feed-package!` | `:seon.await/closed-operation`, `:seon.await/elapsed-ms` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:2998` | `inbound` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:3001` | `inbound` | `:seon.db.write.attempt/completion-unavailable`, `:seon.db.write/attempt` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:3084` | `ensure-namespace-owner!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:3389` | `context-response` | `:seon.agent/no-such-agent`, `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.instrument/check`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/compaction-agent-id`, `:seon.turn/error-turn-id`, `:seon.turn/generated-read-attributes`, `:seon.turn/missing-opening-datom`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:3640` | `start!` | `:seon.db.process/id` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/render/web.clj:3646` | `start!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/repl.clj:489` | `render-html` | `:seon.render.value/root-description` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/repl.clj:495` | `render-html` | `:seon.eval/renderer` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/repl.clj:500` | `render-html` | `:seon.cluster.eval/source` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:298` | `compilable-form` | `:gen/gen` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:304` | `compilable-form` | `:gen/gen` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:572` | `canonical-definition` | `:gen/gen` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:587` | `canonical-definition` | `:gen/gen` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:1400` | `assert-complete-contract!` | `:seon.schema.admission/source` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:1445` | `assert-complete-contract!` | `:seon.error/base`, `:seon.schema.admission/source` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:2573` | `projection-from-rows` | `:seon.schema.admission/source` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:2721` | `projection-from-rows` | `:seon.schema.projection/fingerprint` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:2725` | `projection-from-rows` | `:seon.schema.projection/fingerprint` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:3710` | `identity-only-descriptors-in` | `:seon.schema/identity-only` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema.clj:3875` | `explain-shape-in` | `:seon.schema.projection/shape-rows` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema/datahike.clj:155` | `malli->datahike-attr-in` | `:seon.db/ref` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema/datahike.clj:167` | `malli->datahike-attr-in` | `:db.type/double`, `:db.type/float` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/schema/datahike.clj:186` | `assert-storable-schema!` | `:seon.error/base` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1062` | `installation-covers-program-change?` | `:db/isComponent` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1065` | `installation-covers-program-change?` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1073` | `installation-covers-program-change?` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1085` | `installation-covers-program-change?` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1489` | `documentation-schemas` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1523` | `agent-documentation-contract` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1549` | `function-doc-map` | `:seon.schema.admission/source` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1551` | `function-doc-map` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1556` | `function-doc-map` | `:seon.fn/doc` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1559` | `function-doc-map` | `:seon.fn/arglists` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1577` | `directory-value` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1578` | `directory-value` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1579` | `directory-value` | `:seon.ns/name` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1589` | `directory-value` | `:seon.fn/arglists` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1592` | `directory-value` | `:seon.fn/doc` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1607` | `documentation-value` | `:seon.schema.admission/source` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1611` | `documentation-value` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1612` | `documentation-value` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1613` | `documentation-value` | `:seon.fn/private?`, `:seon.fn/sym` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1619` | `documentation-value` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1620` | `documentation-value` | `:seon.ns/doc` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1734` | `record-acquisition-refusals!` | `:seon.db/connection` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/sci/eval.clj:1740` | `record-acquisition-refusals!` | `:seon.config/error-key` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/search.clj:567` | `index-step` | `::flow/stop` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/test/runner.clj:3602` | `recording-failure` | `:seon.db.write.attempt/request-id`, `:seon.source/refused-test-run`, `:seon.test.run/unavailable`, `:seon.test/execution-refusal` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/test/runner.clj:3606` | `recording-failure` | `:seon.test/run` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/test/runner.clj:3631` | `recording-failure-notice` | `:seon.error/data`, `:seon.operator/events` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:383` | `open-for-agent` | `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:385` | `open-for-agent` | `:db/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:2066` | `generated-read-fault` | `:datahike.query.source/argument-position` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:2072` | `generated-read-fault` | `:seon.db/pattern-attribute` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3194` | `planned-sources` | `::reply/no-forms` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3283` | `gate-function-install` | `:seon.fn/spec`, `:seon.fn/sym`, `:seon.program/row` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3284` | `gate-function-install` | `:seon.fn/spec`, `:seon.program/row` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3291` | `gate-function-install` | `:seon.fn/namespace-unresolvable` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3346` | `gate-function-install` | `:seon.test.accretion/install?` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3353` | `gate-function-install` | `:seon.fn/sym`, `:seon.program/row` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3791` | `settle-batch-refusal!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3833` | `settle!` | `:seon.turn.loop/phase-failed` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3850` | `settle!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn.loop/phase-failed` | `if-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3860` | `settle!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn.loop/phase-failed` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:3925` | `attempt-evidence` | `:seon.ai/interrupted-text-count` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4122` | `record-attempt!` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4244` | `open-turn` | `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/generated-read-attributes`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4263` | `open-turn` | `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/generated-read-attributes`, `:seon.turn/rule` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4357` | `call-turn` | `:seon.config.ai/no-provider` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4404` | `call-turn` | `:seon.config.ai/no-provider` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4436` | `call-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4465` | `call-turn` | `:seon.config.ai/no-provider` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4480` | `call-turn` | `:seon.cluster.prompt/budget-exceeded`, `:seon.cluster.prompt/missing-cluster`, `:seon.cluster.prompt/missing-config`, `:seon.cluster.prompt/no-trigger`, `:seon.cluster.prompt/refused`, `:seon.config/error-key`, `:seon.config/missing-effective`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.web/function-unavailable`, `:seon.render.web/refused-member`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn.loop/phase-failed`, `:seon.turn/missing-opening-datom` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4510` | `call-turn` | `:seon.config.ai/no-provider` | `when-not` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4518` | `call-turn` | `:seon.db/invalid-read`, `:seon.schema/expected-value`, `:seon.turn/missing-opening-datom` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4531` | `call-turn` | `:seon.config.ai/no-provider` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4534` | `call-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4564` | `call-turn` | `:seon.ai/exhausted-finish-reason`, `:seon.ai/extra-body-edn`, `:seon.ai/interrupted-text-count`, `:seon.ai/missing-credential-variable`, `:seon.ai/protected-keys`, `:seon.ai/provider-error`, `:seon.ai/timeout`, `:seon.ai/transport-failure`, `:seon.ai/unanswered-reasoning-count`, `:seon.ai/unreadable-response-member` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4603` | `call-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4660` | `call-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4691` | `disposition-rule-error` | `:seon.sci.admit/value` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4782` | `evaluate-sources` | `:seon.turn/invalid-disposition-source` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4795` | `evaluate-sources` | `:seon.cluster.eval/author`, `:seon.turn.work/situation`, `:seon.turn/id` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4809` | `evaluate-sources` | `:my.turn/disposition`, `:seon.sci.admit/value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4844` | `preview-sources` | `:seon.cluster.reply/no-forms` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4887` | `resume-turn` | `:seon.turn.loop/phase-failed` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4887` | `resume-turn` | `:seon.turn.loop/phase-failed` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4935` | `resume-turn` | `:seon.turn.loop/phase-failed` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:4938` | `resume-turn` | `:seon.fn/namespace-unresolvable`, `:seon.turn.loop/phase-failed` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5018` | `resume-turn` | `:seon.turn.loop/settled`, `:seon.turn.loop/undisposed?` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5039` | `close-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5081` | `generate-turn` | `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5094` | `generate-turn` | `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn.loop/phase-failed` | `cond` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5121` | `generate-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5155` | `generate-turn` | `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.schema/expected-value` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5230` | `turn` | `:seon.agent/id`, `:seon.db/connection`, `:seon.issue/agent`, `:seon.turn.loop/outcome`, `:seon.turn.work/situation` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5238` | `turn` | `:seon.cluster.reply/no-forms`, `:seon.config/error-key`, `:seon.db.write.attempt/request-id`, `:seon.db/invalid-read`, `:seon.render.transcript/refused-member`, `:seon.render.unknown/reason`, `:seon.render.walk/missing-lookup`, `:seon.render/candidates`, `:seon.render/invalid-output`, `:seon.render/refused-member`, `:seon.schema/expected-value`, `:seon.turn/generated-read-attributes`, `:seon.turn/rule` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5254` | `turn` | `:seon.sci.eval/projection-state` | `if-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5550` | `step` | `::flow/stop` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5559` | `step` | `:seon.turn.loop/parked` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5585` | `step` | `:seon.turn/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5595` | `step` | `:seon.turn/id` | `when-let` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5612` | `step` | `:seon.cluster.wake/armer-channel`, `:seon.turn.loop/outcome` | `when` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5654` | `step` | `:seon.agent/ready` | `if` selects the producer’s declared refusal or its success continuation. |
| `src/seon/turn.clj:5657` | `step` | `:seon.agent/turn-backstop-state` | `when-let` selects the producer’s declared refusal or its success continuation. |
