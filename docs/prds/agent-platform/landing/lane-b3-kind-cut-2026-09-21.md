---
type: research
status: incomplete
created: 2026-09-21
---

# B3 kind/class cut — landing evidence

**In progress: regression committed; Slice A settlement conversion verified below.**
The complete kind/class cut still requires Slices B and C. No final green claim.

## Commits and changed paths

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

This table records retained Slice A decisions. Remaining sites are still under
review; it is not yet the completed cut's exhaustive table.

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

Slices B/C remain: other source kind sites, all remaining test/script/bin sites,
class mechanism deletion, and per-callee debt guard review. The original full
namespace matrix and exhaustive B3 commits 1–3 disposition remain outstanding.
Foreign documents and every pre-existing untracked file are preserved. Earlier
foreign test-selection edits were excluded from the baseline snapshot and have
since landed independently.

## Counts before / current

Counts use matching lines, as `rg -c` does. Current counts are the Slice A working source. Multiple class properties in one EDN snapshot line count once.

| Path | Kind before | Kind current | Class marker before | Class marker current | Debt before | Debt current |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `src/my/program.clj` | 0 | 0 | 0 | 0 | 27 | 27 |
| `src/seon/agent.clj` | 11 | 11 | 0 | 0 | 0 | 0 |
| `src/seon/artifact.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `src/seon/call_preparation.clj` | 0 | 0 | 0 | 0 | 12 | 12 |
| `src/seon/cluster/agent.clj` | 17 | 16 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/boot.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/export.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/message.clj` | 17 | 17 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/prompt.clj` | 10 | 10 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/registry.clj` | 6 | 6 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/reply.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/source.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/store.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `src/seon/cluster/wake.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `src/seon/cluster.clj` | 23 | 23 | 0 | 0 | 0 | 0 |
| `src/seon/context.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/db.clj` | 24 | 24 | 0 | 0 | 0 | 0 |
| `src/seon/edit/jvm.clj` | 6 | 6 | 0 | 0 | 0 | 0 |
| `src/seon/effect.clj` | 0 | 0 | 0 | 0 | 2 | 2 |
| `src/seon/eval/drive.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `src/seon/eval.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `src/seon/fn/analyzer.clj` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn/schema_shape.clj` | 2 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn/signature.cljc` | 1 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fn.clj` | 25 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/fs/jvm.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `src/seon/fs.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `src/seon/issue/detect.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `src/seon/issue/opening.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `src/seon/note.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `src/seon/plan.clj` | 0 | 0 | 0 | 0 | 38 | 38 |
| `src/seon/print.cljc` | 3 | 3 | 0 | 0 | 0 | 0 |
| `src/seon/program.cljc` | 0 | 0 | 0 | 0 | 2 | 2 |
| `src/seon/reconcile.cljc` | 2 | 2 | 0 | 0 | 0 | 0 |
| `src/seon/render/data.clj` | 0 | 0 | 0 | 0 | 5 | 5 |
| `src/seon/render/transcript.clj` | 0 | 0 | 0 | 0 | 35 | 35 |
| `src/seon/render/walk.clj` | 0 | 0 | 0 | 0 | 5 | 5 |
| `src/seon/render/web.clj` | 0 | 0 | 0 | 0 | 20 | 20 |
| `src/seon/render.clj` | 0 | 0 | 0 | 0 | 6 | 6 |
| `src/seon/repl.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `src/seon/run.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `src/seon/schema/datahike.clj` | 5 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema/edn.clj` | 12 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema/internal.cljc` | 4 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/schema.clj` | 29 | 0 | 0 | 0 | 0 | 0 |
| `src/seon/sci/eval.clj` | 0 | 0 | 0 | 0 | 13 | 13 |
| `src/seon/sci/kernel.clj` | 0 | 0 | 0 | 0 | 1 | 1 |
| `src/seon/search.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `src/seon/test/accretion.clj` | 0 | 0 | 0 | 0 | 1 | 1 |
| `src/seon/test/runner.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `src/seon/turn.clj` | 56 | 26 | 0 | 0 | 0 | 0 |
| `test/seon/adoption_diagnostic_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/adoption_margin_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/agent_call_edges_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/agent_situation_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/bounded_boundary_census_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/classification_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_arming_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_identity_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_namespace_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/agent_test.clj` | 10 | 10 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/armed_test.clj` | 7 | 7 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/evaluate_sources_test.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/instruction_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/mcp_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/message_assignment_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/message_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/problem_routing_test.clj` | 10 | 10 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/program_restart_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/prompt_test.clj` | 7 | 7 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/registry_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/reply_test.clj` | 6 | 0 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/resume_artifact_routing_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/source_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/status_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/store_transact_test.clj` | 7 | 7 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/turn_test.clj` | 35 | 30 | 0 | 0 | 0 | 0 |
| `test/seon/cluster/wake_test.clj` | 7 | 7 | 0 | 0 | 0 | 0 |
| `test/seon/cluster_test.clj` | 7 | 7 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_independence_test.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_streams_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/concurrency_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_fixture.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_install_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/contracts_plan_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/custody_stability_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/data_shapes_test.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `test/seon/db/declaration_population_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/db_test.clj` | 26 | 26 | 0 | 0 | 0 | 0 |
| `test/seon/dev/changed_test_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/dev/dependency_cache_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/dev/edit_feedback_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/edit/jvm_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/fn/publication_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/fn_test.clj` | 25 | 25 | 0 | 0 | 0 | 0 |
| `test/seon/fs/jvm_test.clj` | 9 | 9 | 0 | 0 | 0 | 0 |
| `test/seon/help_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/help_trial_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/html_views_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/loop_proof_test.clj` | 12 | 12 | 0 | 0 | 0 | 0 |
| `test/seon/mcp_test.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `test/seon/no_provider_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/owned_value_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/public_contract_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/read_evidence_test.clj` | 6 | 6 | 0 | 0 | 0 | 0 |
| `test/seon/reconcile_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/refusal_grammar_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/registry_isolation_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/render/hiccup_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/render_coverage_test.clj` | 5 | 5 | 0 | 0 | 0 | 0 |
| `test/seon/render_simplification_test.clj` | 8 | 8 | 0 | 0 | 0 | 0 |
| `test/seon/render_source_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/repl_grammar_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/repl_parity_test.clj` | 6 | 6 | 0 | 0 | 0 | 0 |
| `test/seon/repl_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/rereads_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/reset_edges_test.clj` | 8 | 8 | 0 | 0 | 0 | 0 |
| `test/seon/returned_error_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/run4_install_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/run4_reader_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/run6_stall_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/schema/admission_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema/datahike_parity.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `test/seon/schema/datahike_test.clj` | 1 | 1 | 1 | 1 | 0 | 0 |
| `test/seon/schema/declaration_population_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema/edn_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema/program_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/schema_test.clj` | 1 | 1 | 2 | 2 | 0 | 0 |
| `test/seon/schema_usage_guard_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/sci/supplied_database_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/source_reconciliation_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/supplied_documentation_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/test_failure_facts_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/test_preparation_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/test_provenance_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/test_reaching_test.clj` | 10 | 10 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_failure_fixture.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_integration_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `test/seon/test_runner_test.clj` | 6 | 6 | 0 | 0 | 0 | 0 |
| `test/seon/test_support.clj` | 7 | 7 | 0 | 0 | 0 | 0 |
| `test/seon/test_support_test.clj` | 6 | 6 | 0 | 0 | 0 | 0 |
| `test/seon/transact_feedback_test.clj` | 4 | 4 | 0 | 0 | 0 | 0 |
| `test/seon/transaction_result_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `test/seon/turn_continue_test.clj` | 2 | 2 | 0 | 0 | 0 | 0 |
| `test/seon/turn_loop_test.clj` | 19 | 19 | 0 | 0 | 0 | 0 |
| `test/seon/turn_test.clj` | 23 | 23 | 0 | 0 | 0 | 0 |
| `test/seon/turn_work_test.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `script/seon/dev/changed_test.clj` | 3 | 3 | 0 | 0 | 0 | 0 |
| `script/seon/dev/dependency_digest.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `script/seon/dev/issues.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `script/seon/dev/mcp.clj` | 1 | 1 | 0 | 0 | 0 | 0 |
| `bin/seon-hook` | 4 | 4 | 0 | 0 | 0 | 0 |
| `resources/seon/schemas/my.fs.edn` | 0 | 0 | 16 | 16 | 0 | 0 |
| `resources/seon/schemas/my.message.edn` | 0 | 0 | 6 | 6 | 0 | 0 |
| `resources/seon/schemas/my.note.edn` | 0 | 0 | 5 | 5 | 0 | 0 |
| `resources/seon/schemas/my.turn.edn` | 0 | 0 | 3 | 3 | 0 | 0 |
| `resources/seon/schemas/my.web.edn` | 0 | 0 | 11 | 11 | 0 | 0 |
| `resources/seon/schemas/seon.agent.edn` | 0 | 0 | 6 | 6 | 0 | 0 |
| `resources/seon/schemas/seon.artifact.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.boot.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.export.edn` | 0 | 0 | 5 | 5 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.prompt.edn` | 0 | 0 | 5 | 5 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.registry.edn` | 0 | 0 | 8 | 8 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.reply.edn` | 0 | 0 | 3 | 3 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.source.edn` | 0 | 0 | 7 | 7 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.store.edn` | 0 | 0 | 6 | 6 | 0 | 0 |
| `resources/seon/schemas/seon.cluster.wake.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.db.edn` | 0 | 0 | 4 | 4 | 0 | 0 |
| `resources/seon/schemas/seon.dev.mcp.artifact.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.dev.mcp.edn` | 0 | 0 | 3 | 3 | 0 | 0 |
| `resources/seon/schemas/seon.eval.drive.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.fn.binding.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.fn.edn` | 0 | 0 | 13 | 13 | 0 | 0 |
| `resources/seon/schemas/seon.message.edn` | 0 | 0 | 5 | 5 | 0 | 0 |
| `resources/seon/schemas/seon.print.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.reconcile.edn` | 0 | 0 | 6 | 6 | 0 | 0 |
| `resources/seon/schemas/seon.schema.datahike.edn` | 0 | 0 | 10 | 10 | 0 | 0 |
| `resources/seon/schemas/seon.schema.edn` | 0 | 0 | 25 | 25 | 0 | 0 |
| `resources/seon/schemas/seon.schema.edn.edn` | 0 | 0 | 8 | 8 | 0 | 0 |
| `resources/seon/schemas/seon.schema.shape.edn` | 0 | 0 | 3 | 3 | 0 | 0 |
| `resources/seon/schemas/seon.search.edn` | 0 | 0 | 2 | 2 | 0 | 0 |
| `resources/seon/schemas/seon.turn.edn` | 0 | 0 | 1 | 1 | 0 | 0 |
| `resources/seon/schemas/seon.turn.loop.edn` | 0 | 0 | 4 | 4 | 0 | 0 |

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
missing base-error admission; their output is now `error/base`, and the
stale-source constructor names `my.edit/stale-source-error`. This is the existing
polymorphic diagnostic-constructor boundary, not a general error discriminator.
Final focused rerun of `seon.fs.jvm-test seon.edit.jvm-test my.turn-test`, with
the three respective source paths: run `6dfa3bbb4eb4`, **20 tests, 162 assertions,
18 failures, zero errors**, recorded, exit 1 (`tmp/b3-values-final.log`). The
remaining assertions still expect retired kind values; Slice C converts them.
Publication `5991b53b-b0ab-4268-85de-25194af175b6` converged at
`6ab2113a-2ef7-50f2-b1a0-4bdb0650f0c1`.
