---
type: research
status: constructor and caller implementation complete; verification limits recorded
created: 2026-09-22
---

# B3 constructor and caller cut

README §4 step 1.1 / B3 §5 rows 1–3. Baseline `d7ac7fd78`;
foreign symbol-test work subsequently landed as `eb4123dba` before this commit.
Literal-map owner groups land first without requiring the new constructor.
The constructor and its remaining callers then land atomically because requiring
`at` is a breaking input change. The inventory below describes the complete
working cut; commit rows distinguish the portions already landed.

The retained leaf is `seon.error.refusal/diagnostic`; the facade is deleted.
The input is open, requires at/layer/operation, and has optional message and
Throwable. Only `:seon.error/throwable` is consumed. Domain members remain intact;
the only derived members are frame and exception-class. A missing complete frame
adds no frame. The stored `:seon.error/cause` remains `:seon.db/ref`.
The existing Throwable schema and base resource require no modification.
The orchestrator owns the concurrent plan rewrite and its constructor paragraph.
No caller output union or wrapper exemption was broadened.

## Dispositions

The named 275 source sites are all covered. Reading actual call forms at the
baseline additionally finds 17 runner sites, one script site and nine test sites:
302 total. Text grep is not a call count (it also matches retired key names).
The 94 B3-owned sites are effect 15, config 18, env 10, bootstrap 5, issue 22,
and plan 24. The five blob sites are A2's owner group.

| Owner group | Baseline calls | Direct map / map expression | Throwable leaf | Retained forwarding call | Deleted facade / obsolete test calls |
|---|---:|---:|---:|---:|---:|
| B3's named 94 | 94 | 93 | 1 | 0 | 0 |
| A2 blob | 5 | 5 | 0 | 0 | 0 |
| Other source owners in the named 275 | 176 | 167 | 7 | 1 | 1 |
| Additional runner sites | 17 | 16 | 0 | 1 | 0 |
| Operator script | 1 | 1 | 0 | 0 | 0 |
| Existing test call sites | 9 | 6 | 1 | 0 | 2 |
| **Total** | **302** | **288** | **9** | **2** | **3** |

The two remaining non-Throwable calls (registration and worker-launch refusal)
pass complete observations to the same leaf; there is no second constructor.
Three new direct constructor calls in the replacement regression are additional
test coverage, not baseline migration sites.

The map audit additionally includes partial await requests and metadata readers:
356 map literals carried retired entries (334 source, two script, 20 test),
including the removed constructor's own output map and obsolete expectations.
Equal sibling values and duplicate labels are dropped; empty evidence and its
fabricated availability label are dropped. Distinct member/expected/offending
values move to their existing canonical members. Distinct attribution or source
observations remain in the existing `:seon.error/data` map; no new schema member
is invented. A conflicting canonical member is retained as data rather than
overwriting the caller's existing member. Await requests and their resource move
together; their bound and outcome remain top-level declared await members.
The parity fixture retires obsolete diagnostic spellings, including its historical
form snapshots; its native storage oracle is unchanged.

### Distinct causes

| Original cause observation | Final disposition |
|---|---|
| effect handler Throwable | Consumed by leaf; frame/class retained. |
| AI transport Throwable | Consumed; endpoint, transport state and message retained. |
| Both edit parse Throwables | Consumed; source and existing parse message retained. |
| Maintenance operator Throwable | Consumed; original ex-data and operator exception class retained; duplicate data labels removed. |
| SCI evaluation Throwable | Consumed; diagnostic record, timeout observation, throw-site message and ex-data retained. |
| SCI failure-admission Throwable | Consumed separately from the original failure value; original failure remains `offending`, admission message remains in the guard observation. The original failure value is a map, not a Throwable. |
| Shell execution Throwable | Consumed; request, argv and exception data retained. |
| Fixture construction Throwable | Consumed; provenance failure and exception data retained. |
| Program read exception message | Retained in source data alongside its class and read operation. |
| SCI namespace acquisition cause message/location | Retained in source data and existing cause-location members. |
| SCI context acquisition cause message/data | Retained in its existing acquisition-cause-message and failure-data members. |
| MCP projection exception message | Retained in source data; the distinct projection operation/class stay available. |
| Invalid lookup's validation/type map | Retained in source data alongside installed declaration and offending value. |
| Source-change analysis/adoption | Existing captured span/source-path and digest-before decide the existing one-retry branch. No replacement kind stamp. |
| Keyword kind causes, including indirect operator, web, schema, DB-diff and await causes | Deleted; schema and existing distinguishing domain members retain the meaning. |
| Render unknown reason / test problem cause | Duplicate labels dropped; the existing reason/offending members remain. |
| Test destructive-owner cause | The observed owner remains in data with its call path and declared destructive boundary. |

### Per-file call accounting

L = direct map/expression; T = consumed Throwable; F = forwarding call; D = retired.

| Baseline file | Calls | L | T | F | D |
|---|---:|---:|---:|---:|---:|
| `script/seon/operator.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/my/background.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/my/program.clj` | 5 | 5 | 0 | 0 | 0 |
| `src/seon/ai.clj` | 17 | 16 | 1 | 0 | 0 |
| `src/seon/await.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/background.clj` | 2 | 2 | 0 | 0 | 0 |
| `src/seon/blob.clj` | 5 | 5 | 0 | 0 | 0 |
| `src/seon/bootstrap.clj` | 5 | 5 | 0 | 0 | 0 |
| `src/seon/call_preparation.clj` | 6 | 6 | 0 | 0 | 0 |
| `src/seon/cluster.clj` | 3 | 3 | 0 | 0 | 0 |
| `src/seon/cluster/agent.clj` | 2 | 2 | 0 | 0 | 0 |
| `src/seon/cluster/boot.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/cluster/process.clj` | 4 | 4 | 0 | 0 | 0 |
| `src/seon/cluster/reply.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/cluster/source.clj` | 3 | 3 | 0 | 0 | 0 |
| `src/seon/config.clj` | 18 | 18 | 0 | 0 | 0 |
| `src/seon/context.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/db.clj` | 4 | 4 | 0 | 0 | 0 |
| `src/seon/edit.clj` | 10 | 8 | 2 | 0 | 0 |
| `src/seon/effect.clj` | 15 | 14 | 1 | 0 | 0 |
| `src/seon/env.clj` | 10 | 10 | 0 | 0 | 0 |
| `src/seon/error.clj` | 1 | 0 | 0 | 0 | 1 |
| `src/seon/eval.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/flow.clj` | 8 | 8 | 0 | 0 | 0 |
| `src/seon/fn.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/instrument.clj` | 2 | 1 | 0 | 1 | 0 |
| `src/seon/issue.clj` | 22 | 22 | 0 | 0 | 0 |
| `src/seon/maintenance.clj` | 7 | 6 | 1 | 0 | 0 |
| `src/seon/plan.clj` | 24 | 24 | 0 | 0 | 0 |
| `src/seon/program.cljc` | 9 | 9 | 0 | 0 | 0 |
| `src/seon/render.clj` | 10 | 10 | 0 | 0 | 0 |
| `src/seon/render/data.clj` | 2 | 2 | 0 | 0 | 0 |
| `src/seon/render/hiccup.clj` | 4 | 4 | 0 | 0 | 0 |
| `src/seon/render/lint.clj` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/render/transcript.clj` | 3 | 3 | 0 | 0 | 0 |
| `src/seon/render/value.clj` | 2 | 2 | 0 | 0 | 0 |
| `src/seon/render/walk.clj` | 3 | 3 | 0 | 0 | 0 |
| `src/seon/render/web.clj` | 9 | 9 | 0 | 0 | 0 |
| `src/seon/schedule.clj` | 7 | 7 | 0 | 0 | 0 |
| `src/seon/schema.clj` | 4 | 4 | 0 | 0 | 0 |
| `src/seon/sci/admit.clj` | 4 | 4 | 0 | 0 | 0 |
| `src/seon/sci/eval.clj` | 12 | 12 | 0 | 0 | 0 |
| `src/seon/sci/kernel.clj` | 3 | 1 | 2 | 0 | 0 |
| `src/seon/sci/reader.cljc` | 1 | 1 | 0 | 0 | 0 |
| `src/seon/shell/jvm.clj` | 9 | 8 | 1 | 0 | 0 |
| `src/seon/test.clj` | 5 | 5 | 0 | 0 | 0 |
| `src/seon/test/accretion.clj` | 2 | 2 | 0 | 0 | 0 |
| `src/seon/test/runner.clj` | 17 | 16 | 0 | 1 | 0 |
| `src/seon/turn.clj` | 5 | 5 | 0 | 0 | 0 |
| `test/seon/effect_test.clj` | 1 | 1 | 0 | 0 | 0 |
| `test/seon/error_test.clj` | 3 | 1 | 0 | 0 | 2 |
| `test/seon/fn_test.clj` | 1 | 1 | 0 | 0 | 0 |
| `test/seon/refusal_grammar_test.clj` | 1 | 1 | 0 | 0 | 0 |
| `test/seon/test/selection_test.clj` | 1 | 1 | 0 | 0 | 0 |
| `test/seon/test_runner_test.clj` | 1 | 1 | 0 | 0 | 0 |
| `test/seon/test_support.clj` | 1 | 0 | 1 | 0 | 0 |

## Live proof

`bin/seon status` and MCP `runtime_status` observed default PID 38968,
start `2026-09-22T12:04:10.581Z`, no missing readiness layers, every proc replying,
and 15 errored receipts. This is readiness evidence, not a green platform.
All MCP forms use root `/Users/sean/src/seon`, cluster `default`, mode `jvm`,
timeout 10000 ms, and an explicit connection for database acquisition.

The initial installed probe established `:seon.error/cause` is `:seon.db/ref`:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.schema/projection-from-database database)
      value {:seon.error/at (java.util.Date. 0)
             :seon.error/layer :x/y :seon.error/operation 'a/b :x/member 1}
      cause (Exception. "constructor probe")]
  {:cause-form (get (:seon.schema.projection/forms projection) :seon.error/cause)
   :base-valid? ((seon.schema/projection-validator projection :seon.error/base) value)
   :retained-throwable-valid?
   ((seon.schema/projection-validator projection :seon.error/base)
    (assoc value :seon.error/cause cause))
   :constructor-contract (:malli/schema (meta #'seon.error.refusal/diagnostic))})
```

`ret`, 127 ms: cause-form `:seon.db/ref`, base-valid true,
retained-throwable-valid false. The owner resolved that collision by choosing
the already-declared Throwable input and consumption rule. No storage widening.

The after probe evaluates the actual edited constructor body from disk in a
scoped immutable projection, arms it and both producing callers, and restores
the installed Var on exit. It does not publish, adopt, restart or reset default.
Exact form:

```clojure
(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      installed (seon.schema/projection-from-database database)
      declaration (with-open [reader (java.io.PushbackReader. (clojure.java.io/reader "src/seon/error/refusal.clj"))]
                    (first (filter #(and (seq? %) (= 'defn (first %)) (= 'diagnostic (second %)))
                                   (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} reader))))))
      contract (:malli/schema (nth declaration 3))
      implementation (eval (cons 'fn (drop 4 declaration)))
      admission {:seon.schema.admission/source :core}
      projection (-> installed
                     (seon.schema/projection-with-function-contract 'seon.error.refusal/diagnostic contract admission)
                     (seon.schema/projection-with-schema :x/member :int admission)
                     (seon.schema/projection-with-schema :x/y-error [:and :seon.error/base [:map [:x/member [:= 1]]]] admission)
                     (seon.schema/projection-with-schema :x/z-error [:and :seon.error/base [:map [:x/member [:= 2]]]] admission))
      caps (seon.config/result-caps seon.config/defaults)
      constructor (seon.instrument/wrap-interpreted 'seon.error.refusal/diagnostic (pr-str contract) projection :panic caps implementation)]
  (seon.schema/call-with-projection
   projection
   (fn []
     (with-redefs [seon.error.refusal/diagnostic constructor]
       (let [value (seon.error.refusal/diagnostic {:seon.error/at (java.util.Date.) :seon.error/layer :x/y :seon.error/operation 'a/b :x/member 1})
             accepted (seon.instrument/wrap-interpreted 'x/accepted "[:=> [:cat :map] :x/y-error]" projection :panic caps seon.error.refusal/diagnostic)
             refused (seon.instrument/wrap-interpreted 'x/refused "[:=> [:cat :map] :x/z-error]" projection :panic caps seon.error.refusal/diagnostic)
             refusal (try (refused value) (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
         {:value value :key-count (count value) :accepted (= value (accepted value))
          :refused-operation (:seon.error/operation refusal)
          :refusal-valid? ((seon.schema/projection-validator projection :seon.instrument/undeclared-error) refusal)})))))
```

`ret`, **167 ms**, 2026-09-22T14:48:20Z:

```clojure
{:value {:seon.error/at #inst "2026-09-22T14:48:20Z"
         :seon.error/layer :x/y :seon.error/operation 'a/b :x/member 1}
 :key-count 4
 :accepted true
 :refused-operation 'x/refused
 :refusal-valid? true}
```

MCP's rendered envelope represents keywords/symbols as strings; the values
above use their Clojure types. The refusal message names x/y-error as undeclared. This proves the live JVM
path with edited source under scoped arming, not cluster adoption freshness.

## Focused test evidence

Every test invocation was `bin/test-fast --paths <owned paths> -- <namespaces>`.
No direct `bin/test`, full suite, cold gate or platform run was invoked.
`tmp/b3-owned-paths.json` records the source/test path list for the final requests.
Pure constructor tests use the runner's entering armed projection. A database
fixture would instead carry the older published function contracts, positively
observed in run `fbfe245b69f2`; no production fixture is fabricated to hide that.

| Run / log | Executed / assertions | Result |
|---|---:|---|
| `d072f5ac08a1`, `tmp/b3-constructor-before.log` | 5 / 21 | 6 failures, 4 errors against old constructor; recording refused. Open-map call fails on the old required message. Initial synthetic-wrapper setup also lacked the member declaration and was corrected. |
| `fbfe245b69f2`, `tmp/b3-constructor-after-1.log` | 4 / 8 | Recorded, 2 failures, 5 errors: fixture supplied old constructor input contract; literal regression also exposed a remaining forwarding call. |
| **`75efe05d99a2`**, `tmp/b3-constructor-after-2.log` | **4 / 11** | **Recorded: 0 failures, 0 errors.** Plain/sorted-map preservation, Throwable consumption and stored ref preservation, exact producing-caller return enforcement, facade absence and literal reader refusal. |
| `tmp/b3-error-after-1.log` | 0 | Test compile failure: a shared generator was removed with the prose blocks. Restored before rerun. |
| `1b8879d8640a`, `tmp/b3-error-after-2.log` | 51 / 332 | Recorded: 4 failures, 7 errors; constructor passed, recording/read and old await fixture boundaries red. |
| `cd3b4e19857e`, `tmp/b3-owners-after-1.log` | 34 / 129 | Recorded: 6 failures, 8 errors; effect/maintenance fixture and retired mechanism boundaries, duration bounds and an existing message assertion. |
| `bc5b325de5ed`, `tmp/b3-final-focused.log` | 96 / 649 | 26 failures, 11 errors; result recording refused an evidence transition. No green reuse claimed. |

The required error regression classes remain: declared/undeclared returns,
sorted-map input, signature invariance, repeated occurrence identity and recording.
Obsolete diagnostic-envelope, union and prose-only blocks were removed rather
than asserting fabricated unknown evidence. Their surviving assertions now read
canonical fields. The last await fixture used only pure derivations, so it also
uses the current armed projection instead of acquiring an obsolete database world.

### Verification limits and foreign boundaries

The focused runner snapshots HEAD plus only declared paths. Its copied database
fixture still supplies old constructor/registration/await contracts in database
scopes. This is the ambient projection transport boundary assigned to a later cut;
it is not permission to restore retired keys or weaken the new input.
The read wrapper's declared error schema refusals prevent several recording/occurrence tests from
reaching their subject. Schema-shape and registry-derived complete-union checks and rendering-prose
expectations also remain red. None was repaired by widening caller unions.
A separate unchanged-HEAD request checks attribution of those existing reds.

The current-source hook was already disabled and remains disabled. No publication
path was changed or measured, and no adoption clock or platform pass is claimed.
The stopped partial wrapper patch was read as evidence and was not applied.
The hook's bb-lint/dependency cache and existing markdown gitlink citations are
outside this cut. A disposable probe-file lint rejection for missing requires was
resolved by adding explicit requires; no approval bypass was used.

Malli is pinned at `606083c5c5b388e84d169c7080af33ed3ec242ae`.
Its compiled function input/output validators (`reference-code/malli/src/malli/core.cljc`)
are acquired at wrapper compilation; each call validates supplied arguments/result,
not a rebuilt schema population. The first-party seam is `compiled-wrapper` and
its existing per-arity `::base?` exemption. Clojure's stack-frame representation
was checked against core_print at pinned `b18d3adc5b5f4d5d0ccea966203fb67a614d5c3d`.
No dependency, cache or wrapper algorithm was added.

## Files and commit/load evidence

Working-tree prescribed require exited 0 before commit (`tmp/b3-load-working.log`
and `tmp/b3-load-final-working.log`). These are working-tree loads, not isolated
HEAD proof. The atomic commit and its HEAD load are recorded below after creation.
The two foreign dirty documents and all pre-existing untracked files remain intact.
No worktree was created, no foreign session was resumed or messaged, and no
operator lifecycle action was performed on default. Test launchers retain their
snapshots as evidence; no uncertain shared root is deleted.

Owned changed paths (including this note):

- `resources/seon/schemas/seon.await.edn`
- `script/seon/dev/dependency_digest.clj`
- `script/seon/operator.clj`
- `src/my/background.clj`
- `src/my/program.clj`
- `src/seon/ai.clj`
- `src/seon/await.clj`
- `src/seon/background.clj`
- `src/seon/blob.clj`
- `src/seon/bootstrap.clj`
- `src/seon/call_preparation.clj`
- `src/seon/cluster.clj`
- `src/seon/cluster/agent.clj`
- `src/seon/cluster/boot.clj`
- `src/seon/cluster/process.clj`
- `src/seon/cluster/reply.clj`
- `src/seon/cluster/source.clj`
- `src/seon/config.clj`
- `src/seon/context.clj`
- `src/seon/db.clj`
- `src/seon/edit.clj`
- `src/seon/effect.clj`
- `src/seon/env.clj`
- `src/seon/error.clj`
- `src/seon/error/refusal.clj`
- `src/seon/eval.clj`
- `src/seon/flow.clj`
- `src/seon/fn.clj`
- `src/seon/instrument.clj`
- `src/seon/issue.clj`
- `src/seon/maintenance.clj`
- `src/seon/plan.clj`
- `src/seon/program.cljc`
- `src/seon/render.clj`
- `src/seon/render/data.clj`
- `src/seon/render/hiccup.clj`
- `src/seon/render/lint.clj`
- `src/seon/render/transcript.clj`
- `src/seon/render/value.clj`
- `src/seon/render/walk.clj`
- `src/seon/render/web.clj`
- `src/seon/schedule.clj`
- `src/seon/schema.clj`
- `src/seon/sci/admit.clj`
- `src/seon/sci/eval.clj`
- `src/seon/sci/kernel.clj`
- `src/seon/sci/reader.cljc`
- `src/seon/shell/jvm.clj`
- `src/seon/test.clj`
- `src/seon/test/accretion.clj`
- `src/seon/test/runner.clj`
- `src/seon/turn.clj`
- `test/my/examples_test.clj`
- `test/my/note_test.clj`
- `test/my/program_mutation_test.clj`
- `test/my/program_test.clj`
- `test/seon/await_test.clj`
- `test/seon/call_preparation_test.clj`
- `test/seon/cluster/agent_test.clj`
- `test/seon/cluster/armed_test.clj`
- `test/seon/cluster/mcp_test.clj`
- `test/seon/cluster_test.clj`
- `test/seon/contracts_install_test.clj`
- `test/seon/contracts_plan_test.clj`
- `test/seon/db_test.clj`
- `test/seon/dev/dependency_cache_test.clj`
- `test/seon/effect_test.clj`
- `test/seon/env_test.clj`
- `test/seon/error/refusal_test.clj`
- `test/seon/error_test.clj`
- `test/seon/flow_configuration_test.clj`
- `test/seon/flow_test.clj`
- `test/seon/fn_test.clj`
- `test/seon/instrument_test.clj`
- `test/seon/issue_test.clj`
- `test/seon/maintenance_test.clj`
- `test/seon/owned_value_test.clj`
- `test/seon/problems_test.clj`
- `test/seon/refusal_grammar_test.clj`
- `test/seon/render/data_test.clj`
- `test/seon/render/lint_test.clj`
- `test/seon/render/page_review_test.clj`
- `test/seon/render/retained_test.clj`
- `test/seon/render/transcript_run_test.clj`
- `test/seon/render/transcript_test.clj`
- `test/seon/render/web_test.clj`
- `test/seon/render_coverage_test.clj`
- `test/seon/render_source_test.clj`
- `test/seon/schema/datahike_parity.edn`
- `test/seon/schema_test.clj`
- `test/seon/sci/eval_test.clj`
- `test/seon/shell/jvm_test.clj`
- `test/seon/test/selection_test.clj`
- `test/seon/test_runner_test.clj`
- `test/seon/test_support.clj`
- `test/seon/test_support_test.clj`
- `test/seon/test_test.clj`
- `test/seon/transact_feedback_test.clj`
- `test/seon/turn_test.clj`
- `docs/prds/agent-platform/landing/lane-b3-constructor-2026-09-22.md`

### First owner group

`20ee7864b` committed the 79 literal construction sites in config, env,
bootstrap, issue and plan, plus the two changed test files. Effect's 15 sites
remain with the constructor group because its handler failure supplies a Throwable.
The prescribed require exited **0** from a clean `git archive` of HEAD
`2ccf5bc90` (which includes `20ee7864b`), using only the pinned dependency links,
not the remaining uncommitted source. Log: `tmp/b3-head-first-load.log`.
No worktree was used. The archive's JVM exited before further verification.

The pre-ruling focused correction request was interrupted by TERM with no tally
or run id (`tmp/b3-render-correction.log`); it is not evidence of a pass.
The existing schema-discovery tests and all calls to that deferred owner remain
byte-identical. Their implementation of the new rule and retirement belong to the later lane.

### Blob owner group and baseline comparison

`e569532dd` committed all five blob sites. Its clean archived HEAD passed the
prescribed require, exit **0**, `tmp/b3-head-blob-load.log`.
The parity fixture check returned `{:native true :historical-native true
:attributes true}` comparing current and historical native storage data with HEAD.

The unchanged-HEAD comparison used only the unchanged error schema resource as
its `--paths` input and selected `seon.error-test seon.instrument-test`:
`tmp/b3-baseline-owner-reds.log`, **92 tests / 671 assertions / 11 failures /
7 errors**. Its recording was refused. It reproduced the occurrence/read failures,
duration failures, unavailable recording boundary and deferred schema-discovery
checks. These are existing reds, not a reason to change a later lane's mechanism.
The additional stale-constructor/registration errors in the edited run explicitly
name retired input keys from the published fixture.

The comparison also exposed an introduced reader error: passing at/layer through
the evidence reader activated its existing early return and skipped value rendering.
The reader now selects only operation/member/expected/offending alongside its
existing data, preserving the rendering path. No rendering mechanism was added.

### Final constructor/caller request

`16c5872003db`, `tmp/b3-final-constructor-caller.log`: **56 executed tests,
362 assertions, 29 failures, 5 errors**. Snapshot result recording refused the
transition; no recorded green is claimed for this request. All four new constructor
regressions and the five await regressions completed without failure/error events.
The restored, unchanged schema-discovery/cause-reader tests remain red, as do
instrumentation tests entering the old database-fixture contracts. The rendering
value-projection regression no longer failed after the reader correction; the
SCI-only argument-list expectation still failed under the published fixture and
is recorded as unresolved evidence, not a proven pass.

The exact seven-key scan over `src script bin test` prints nothing (exit 1).
Changed Clojure files parse, `git diff --check` passes, and the protected discovery
owner and its caller lines have no diff. No input/output validation was relaxed
in response to a red test. The two unrelated dirty documents remain outside every
commit. The plan rewrite was committed by its owner, not staged by this lane.

### Final committed HEAD and cleanup

`9bf22ecd9` commits the constructor, facade retirement, all remaining caller and
reader changes, the await request resource and the remaining test conversions.
Its clean archived HEAD passed the prescribed require, exit **0**:
`tmp/b3-head-constructor-load.log`. Thus each of the three implementation commits
has a successful HEAD load, independent of uncommitted source.

The three implementation commits touch **100 distinct files**, exactly the list
above: 2,273 inserted lines and 4,249 removed lines, net **1,976 lines removed**
(including the landing note as it stood in the third commit).

| File | Lines before → after | Bytes before → after |
|---|---:|---:|
| Constructor/reader leaf | 132 → 85 | 7,875 → 4,607 |
| Error owner | 2,658 → 2,638 | 145,973 → 145,085 |
| Constructor/reader tests | 90 → 153 | 4,582 → 8,181 |
| Error tests | 1,437 → 1,251 | 81,851 → 70,693 |

All owned test/load shells have exited. The three plain archive directories were
removed only after their JVM exits and a process-argument holder check; dependency
symlinks were unlinked before removal, leaving their targets intact. Run logs and
probe evidence remain in `tmp/`. No uncertain shared test snapshot was swept.
Final MCP readiness still reports the same default process, no missing layers,
all procs replying and 15 errored receipts. No lifecycle or adoption action occurred.

The final evidence-only commit changes no executable input. Its post-commit load
log is `tmp/b3-head-evidence-load.log`; the final response reports its exit status.


### Platform follow-up: flat operation assertions

The three failures in `tmp/platform-gate-2026-09-22-after-1.1.log:1158-1171`
were retired expectations, not a missing operation. `refusal-data`
(`test/seon/test_support.clj:869`) returns the flat map unchanged;
`violation` (`src/seon/instrument.clj:379`) constructs the operation at the top
level. Corrected three assertions in `test/seon/env_test.clj` and the one sibling
assertion in `test/seon/call_preparation_test.clj`. A multiline search across
`test/` finds no remaining nested operation access. Domain-specific nested data
assertions retain their existing meaning.

`bin/test-fast --paths test/seon/env_test.clj -- seon.env-test` passed, exit 0:
run `0f5b17e81d70`, 5 executed, 0 reused, 73 assertions, 0 failures, 0 errors.
Log: `tmp/b3-followup-env.log`. The snapshot was HEAD `0358eb0ac` plus only the
env test change; contracts reported 1,689 registered and instrumented Vars.
The sibling assertion receives the same correction; it was not part of this run.
The concurrently edited production wrapper was read only. Unrelated sibling-file
changes were excluded by staging only this assertion against HEAD. Changed files
are the two test files and this note; no production or plan file was edited.
The post-commit prescribed HEAD-load evidence is `tmp/b3-followup-head-load.log`.

Implementation commit `2add67c85` passed the prescribed twelve-namespace require,
exit 0, from a plain archive of that commit with the existing dependency checkout
linked. This excluded all concurrent source edits. An initial archive setup omitted
the dependency link and failed classpath construction; after linking dependencies,
the actual namespace load passed. No worktree or default operation was used.
The load shell exited; its owned archive was removed after checking process arguments
and unlinking the dependency symlink. The env test shell also exited. This final
note changes no executable input. The sibling namespace was not separately run.
