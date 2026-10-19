---
title: "live run 12: six chains on e19694162, two done and merged, one correct send-back, median 21 turns to green"
type: evidence
status: "run complete 2026-10-19: 2 of 6 done and merged (entity-pairs at 21 calls, malformed contract at 32), 1 correct send-back, 3 capped; cached share 87%; no heap crossing in process 73980; grades now record the provider-reported model (e4cf270bd), but DeepSeek reports the requested name, so the model window cannot see its 10-17 swap (filed red); unresolved-require fix e114c5858"
---

# Live run 12: six chains, the first run graded by commit and model

Lane intake-run12 (Opus). Default JVM pid 73980 was restarted by root onto e19694162 at 08:25:49 UTC (ready 16.3 s).
There was one root act: an SCI request as root at 08:26:40 UTC with six `seon.task/chain!` calls in run 11's form:
- template `seon.step.implement/template`;
- plan and proofs read from the filed task on main;
- chain config `{:seon.config.ai/no-provider :seon.config/absent :seon.config.run/max-episode-runs 40}`;
- model `deepseek-flash` and `:seon.config.agent/max-running` 4, main's effective values;
- task names carry the suffix `-run-12`.

The form is in `tmp/run12/start.clj`. The request cost 1,801 ms and 884 MB of evaluation for six chains (request
2,051 ms, 1,878 MB, 105,420 calls). That is run 10's unfiled start cost (1,105 ms and 598 MB for four), and it is
filed as `seon.task/starting-three-chains-costs-a-gigabyte`.

Hand acts on the agents: 0. Everything else was JVM-mode reads: grades, `seon.mine/grade`, the evaluations and
MemoryPoolMXBean. There were three SCI requests as this lane's own agent `intake-run12-probe`, on its own branch,
to probe a candidate task. Four chains ran at once. The other two started when the bailed attempts had closed, at
about 08:30. The last one ended at 08:31:30 UTC. Scratch output is in `tmp/run12/`.

## Step 1: grades record the model

DeepSeek began serving `deepseek-flash` as V4.1-Flash-1017 on 2026-10-17. e4cf270bd makes the metric see such a
change:
- `seon.ai` keeps the response's `"model"` member (`:seon.ai/response-model`) from a one-shot body and from stream
  chunks.
- `seon.turn` records it on the attempt as `:seon.ai.attempt/response-model`.
- `seon.mine/grade-entity` writes the attempt's distinct reported models as `:seon.grade/models`.
- `seon.mine/report` groups its windows by commit and models.

The proofs were red first on the lane branch:
- `seon.ai-test/the-model-the-provider-reported-is-kept-from-either-transport`;
- `seon.mine-test/the-report-splits-a-commit-by-the-model-the-provider-reported`;
- a models row in `seon.mine-test/an-ended-attempt-is-graded-once-on-main`, where the local test provider now reports
  a model.

The same commit refines `seon.guard-census`, and root confirmed the change. A general catch counts as the interop
edge's own catch when the function declares its error variant and the handler keeps the exception. `clojure.data.json`
throws only `java.lang.Exception`, so before this the census refused any change to `seon.ai/stream-event`, whose catch
is exactly that edge. The proof is the `parses`/`drops` rows in `seon.guard-census-test`.

**What the run showed:** every one of the run's 212 attempts reported `deepseek-flash`, the requested name.
`seon.ai.attempt/usage-edn` and the attempt rows agree. DeepSeek echoes the alias, so the response model cannot show
a swap behind the name. The window is still right for a provider that names its model, and it costs nothing. The
build identity is filed red (Filings).

## The metric

`(my.root/turns-report)` on default after the run. It now reads run 10's and run 11's grades back from storage
without refusing: the Datahike fork bump b224d0e50 reads a stored tuple back as the vector it was written as. That
retires `seon.mine/the-turns-report-refuses-grades-read-back-from-storage`, run 11's Broken 3.

| window | models | attempts | green | median turns | median / p90 turns to green |
|---|---|---|---|---|---|
| ab89888ef (run 10) | none recorded | 4 | 0 | 40 | none |
| 5e8426095 (run 11) | none recorded | 4 | 2 | 32 | 23 / 32 |
| e19694162 (run 12) | `deepseek-flash` | 6 | **2** | 39 | **21 / 32** |
| whole post-nuke series | | 14 | 4 | | 23 / 32 |

Top producers over all 14 grades: `my.program/render-source-ai` 46 wasted forms, `seon.context/current-state` 34 and
`seon.render.value/show` 8. Run 12 alone added 29 and 23. I read the forms behind them: they are the model's re-reads
and its `in-ns` habit, as in runs 10 and 11 (Producer attribution).

## Task selection

There were six tasks: four ordinary fixes, one retired-assumption test fix and one cost task. All six were screened
in one check JVM, run `96a3f386180d`: the candidate HEAD plus the slice, `seon.program-test`, `seon.error-test`,
`seon.render.var-test`, `seon.render.ns-test`, `seon.render.entity-pairs-test`, `seon.task-ingestion-test` and
`seon.fn-test`. It ran 256 tests in 68 s with 16 red. Every proof was red, and task ingestion was green. The new
proofs and the task proofs were committed in e19694162.

| chain | task | kind | proof at HEAD | other reds in reach |
|---|---|---|---|---|
| `5be74ff65b04` program | `seon.program/a-declared-attribute-without-a-registered-schema-is-refused` (retry; run 11's Broken 4 fixed in 6aa630885) | producer or retired assumption | both proofs error, "The attribute has no registered schema." | `indexed-and-evaluated-...` and `typed-cross-...` in the same namespace, as in run 11 |
| `8070210bd188` typed | `seon.program/typed-cross-namespace-deletion-leaves-function-and-test` | producer or retired assumption | red at `program_test.clj:1209` | as above |
| `5ff950ce2f17` unres | `seon.error/an-unresolved-symbol-request-costs-thirty-megabytes` (retry; run 11's Broken 2 fixed in 7c9db54b6) | cost | red, 11,214,400 bytes against 2 MB | two `seon.error-test` reds that do not reach `nearest-declared` |
| `f63fa056f780` var | `seon.render.var/a-malformed-contract-renders-as-missing` | ordinary | new `seon.render.var-test/a-malformed-contract-renders-as-malformed-with-mallis-refusal`, red: `:missing` | none |
| `13cdf64c0222` nsform | `seon.render.ns/the-namespace-form-face-quotes-the-name-dir-reads` | ordinary | new `seon.render.ns-test/the-namespace-form-face-is-the-dir-that-reads-it-again`, red | **`seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`, not screened** (Broken 1) |
| `f7070c2dc41a` pairs | `seon.render/entity-pairs-tests-assert-a-retired-render-interface` | retired assumptions | its three tests red | none |

Rejected:
- `seon.turn.batch/a-refused-settlement-leaves-its-ns-unmap-in-the-context`: it did not reproduce on 494ff6a42.
  `(defn user2 [x] (helper2 x)) (my.program/ns-unmap! 'r12.scratch/helper2)` in one request is refused up front, and
  `(helper 1)` still answers 2 afterwards. It may be moot since 7c9db54b6. It stays filed for its owner to confirm.
- `seon.cluster.source/a-user-namespace-change-publishes-a-test-user-file`: the plan asks the owner to decide first,
  and its proof needs the long write-back fixture.
- `seon.sci.eval/entering-a-test-namespace-...`: in lane runtime-leaf's evaluator area, moot under the new runtime.

## Summary

Wall is first call opened to last call closed. Tokens are prompt / cached / completion.

| chain | end | calls | wall | tokens p / c / out | cached | forms (in-ns) | first defn | wasted calls | agent errors | eval ms / MB |
|---|---|---|---|---|---|---|---|---|---|---|
| pairs | **done at 21, merged** c9e5f3146 | 21 | 48.5 s | 313,683 / 253,312 / 4,361 | 81% | 28 (2) | none (test edits) | 7 | 0 | 356 / 356 |
| var | **done at 32, merged** bd8bd428a | 32 | 67.5 s | 523,070 / 450,176 / 6,348 | 86% | 52 (7) | form 22 (call 13) | 9 | 5 | 525 / 413 |
| nsform | **sent back at 39**, correctly | 39 | 117.4 s | 749,093 / 629,120 / 11,510 | 84% | 50 (7) | form 6 (call 3) | 12 | 5 | 634 / 509 |
| unres | **capped**, proof red, 11.2 MB down to 7.2 MB | 40 | 111.5 s | 920,161 / 823,424 / 10,666 | 89% | 113 (27) | form 57 (call 20) | 5 | 4 | 6,807 / 2,063 |
| program | **capped**, proofs red | 40 | 92.1 s | 904,002 / 782,208 / 7,177 | 87% | 76 (7) | form 56 (call 29) | 13 | 6 | 857 / 812 |
| typed | **capped**, proof red | 40 | 116.3 s | 996,770 / 875,904 / 14,891 | 88% | 78 (7) | never | 13 | 5 | 2,476 / 1,351 |

Totals:
- 212 calls and 4,406,779 prompt tokens, of which 3,814,144 were cached (**87%**);
- 54,953 completion tokens;
- 397 forms, 57 of them `in-ns` (14.4%; 27 in unres);
- 59 wasted calls (by the grade's rule) and 25 agent errors (6.3% of forms);
- 553 s of summed wall;
- 11.7 s of evaluation and 5.5 GB allocated.

The largest form was unres's last, `(my.test/check {})` at call 40. It took 5,266 ms and 946 MB to run the 20 tests
that reach its changes, about 260 ms per test, and it left the proof red at 7,154,456 bytes against 2 MB.

## Broken things first

1. **My screen missed a red reaching test, so the nsform task could not be done as filed.**
   `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain` was red on HEAD in four rows.
   The task's plan names that test, and I did not screen it. Its fifth row asserts the quoted `(dir (quote ns))` the
   task removes.

   The agent's send-back at call 39 is correct and exact. It named the four rows, whose producers are
   `seon.render/render-form` and `seon.cluster.agent/identity-form` on main, and the fifth row that contradicts the
   plan. It also reported that its own proof was green with `(list 'dir (:seon.ns/name unit))`.

   Fix for intake: screen every test a plan names, as well as the proofs. The history test's four pre-existing reds
   need their own task. They are unfiled here and are in Next run.
2. **Two HEAD reds had no task. Both are filed now, each with its existing red proof:**
   - `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses`. Capture `d3597309628b` is prose plus a
     fenced lone `)`. `seon.turn/segmented-sources` calls the dropped delimiter "tool-call markup", because any
     `:seon.batch/dropped` repair counts as markup (since aec253eb6). Task:
     `seon.turn/a-lone-dropped-delimiter-is-called-tool-call-markup`.
   - `seon.data-shapes-test/listen-patterns-retain-optional-entity-and-logical-value`. A cardinality-many pull answers
     a set where the test compares a vector; `:seon.grade/models` pulls as a set too. Task:
     `seon.db/a-cardinality-many-pull-answers-a-set-where-a-test-reads-a-vector`.
3. **The var chain merged 8 minutes after its done.** It ended `:done` at 08:27:38 UTC. `seon.cluster.agent`'s
   `chain-finishes` still listed it at 08:33, with no merge running, no error recorded and no problem task. It merged
   as bd8bd428a at 08:35:35. The pairs chain, which ended later, merged within a second of its done (08:31:37).

   My hypothesis is unverified: `merge-chains!` met `seon.merge/main-moved?` while the other chains were writing
   main, returned false, and waited for a later wake. Nothing is lost, but a done chain's merge then waits on
   unrelated activity. It is not filed, because there is no reproduction yet.
4. **The merged var change carries two review items.**
   - `seon.render.var/malformed-refusal` answers `{}` when `seon.fn/contract-findings` refuses (`(if (vector? report)
     ... {})`). That is a fence over a declared variant.
   - `:seon.render.var/malli-refusal` is a new key in the status data, but `:seon.render.var/status` does not declare
     it. The example offered for a malformed contract is still the example for a missing one.

   The merged pairs change asserts `(str emitted)` contains a phrase, which prints a value in order to compare it.
5. **A pull of an uninstalled attribute answers a whole stack trace.** My own read `(seon.db/pull h [...
   :seon.task.attempt/counterexample] ...)` answered `:seon.error/chain` with 50 frames. The same mistake in
   `seon.db/q` answers a short refusal that names the registered candidates. This is one instance, unfiled.
6. **One listener error at merge time.** `seon.log` at 08:31:37: `:datahike/listener-error` "query canceled" on
   `:seon.agent.chain-route/chain-f7070c2dc41a`, the chain route of the pairs chain that was just merged. Nothing
   followed from it, and it is unfiled.

## Refusals, per chain (read from the forms)

| chain | what the block showed | acted on? |
|---|---|---|
| pairs | the three red tests | **yes**: each assertion was rewritten to the installed interface. Data replaced printed text in row 3, the four-argument `inline-rendered` in row 2, and the namespace page's no-source rule in row 1. Done at 21 |
| var | the red proof; then, after the call-24 `defn status`, the new `:malformed` value against the `:seon.render.var/contract` enum | **yes**: calls 30 to 32 read `:seon.render.var/status` and `:seon.render.var/contract`, then re-registered the enum with `:malformed`. Done at 32 |
| nsform | the red proof, then the red history test after the call-3 `defn` | **yes**: the step verdict was read at call 17, the producers of the four rows were read (calls 18 to 26), and it sent back with the right reason |
| unres | the red proof (11.2 MB, then 7.2 MB) and the replaced helpers | partly: `ns-unmap!` of the unused helpers in callers-first order (calls 25, 28, 33), none refused. It ran out of calls with the cost at 3.6x the bound |
| program | both red proofs | no change of course: it again chased the test loader (`ClassLoader`, `proxy`, `io/resource` at calls 34 to 40), as in run 11 |
| typed | the red proof | no `defn`; 17 text searches through the deletion path (`my.program/locate`, `deletion-report`, `seon.turn/row-tx`) |

## Producer attribution (verified by reading the forms)

| producer | forms / calls | chains | verdict | action |
|---|---|---|---|---|
| `seon.error/reply-value` on an unresolved `seon.test-support/with-database`: it lists `with-database` among `seon.test-support`'s names and never says the namespace is unloaded | 5 forms over 5 calls (nsform 16, 20, 23, 29; var 23) | nsform, var | **real** | **fixed** e114c5858: `:seon.error/fix` names `(require 'seon.test-support)` when the namespace declares the very name. Proof `seon.error-test/a-name-its-namespace-declares-but-the-context-has-not-loaded-offers-the-require`, 20 reaching tests green by branch evidence |
| `in-ns` before each call's forms, charged to `seon.context/current-state` | 27 of unres's 113 forms (14 charged), 7 in each other chain | all | model behavior: unres switched between `my.attempt-1` and `seon.error` each call and re-entered the namespace it was in | none |
| `:re-read` charged to `my.program/render-source-ai` | 29 forms | all | model behavior, as in runs 10 and 11 | none |
| a definition refused admission ("bare nilable return" on `[:maybe ...]`), then `ns-unmap!` of it answers "No program declaration names" | var forms 22 to 24 | var | the refusal is right, and the reply says the Var exists only in the SCI context | none |
| the agent's own slips: an alias from another namespace (`sfn/` in `my.attempt-*`), a vector where `my.test/check` takes a set, a prose line in parentheses read as a call (`(run this ...)`), `(my.test/run 'sym)` | about 8 forms | var, typed, unres | model behavior; each reply named the cause | none |
| `seon.schema/projection-with-schema` refused a copy of the shipped `:seon.fn/fn` with one optional key added, because "`seon.render.ns/function-ai` has no declared contract" | program form 48 | program | unverified: the projection was built by the agent from packaged forms | none |

## Runs 7 to 12

| measure | run 7 (5 render, d76806d39) | run 8 (3 real, c87f0fb77) | run 9 (run 8's tasks, d24ad58e7) | run 10 (4 cost/design, ab89888ef) | run 11 (mix of 4, 5e8426095) | run 12 (mix of 6, e19694162) |
|---|---|---|---|---|---|---|
| done / merged | 1 of 5 merged | 0 of 3 | 1 of 3 merged (+1 correct send-back) | 0 of 4 | 2 of 4 done, 1 merged | **2 of 6 done, 2 merged** (+1 correct send-back) |
| turns to green | n/a | none | 17 | none | 23 and 32 (median 23) | **21 and 32 (median 21, p90 32)** |
| calls per chain | 39 | 40 | 27 | 40 | 34 | 35 (21, 32, 39, 40, 40, 40) |
| wall per call | 2.3 to 2.8 s | 3.8 to 4.6 s | 2.4 to 2.7 s | 1.5 to 3.8 s | 2.0 to 3.2 s | 2.1 to 3.0 s |
| cached share | 87% | 22% | 87% | 90% | 87% | 87% |
| `in-ns` share of forms | 9.8% | 2.4% | 10.4% | 7.3% | 5.0% | 14.4% (6.2% without unres) |
| agent errors per form | 12% | 6% | 12% | 5% | 12% | **6.3%** |
| wasted calls (grade) | 47 | 25 | 19 | 18 | 34 | 59 over 6 chains (9.8 per chain; run 11 8.5) |
| first write | form 49 to 90 | form 20, 27, never | form 36, 35, none | form 16, 53, 45, never | form 31, 55, 25, 50 | form 6, 22, 56, 57, never, none |
| evaluation per chain | 0.7 to 1.7 s, 1.1 to 3.0 GB | 3.8 to 6.5 s, 1.9 to 2.8 GB | 0.4 to 0.7 s | 0.6 to 2.1 s | 0.3 to 1.8 s | 0.4 to 6.8 s, 0.4 to 2.1 GB |
| largest form | 1,207 ms | 950 ms | 97 ms | 164 ms | 226 ms | 5,266 ms (`my.test/check {}`, 20 tests) |
| heap (old gen peak) | 836 MB | 1,178 MB | 1,026 MB | 812 MB | 798 MB | 1,997 MB with this lane's and other lanes' JVM reads; no crossing |
| model window | | | | | | `deepseek-flash` (provider-reported) |

What improved:
- Both chains that ended done also merged, a first for the post-nuke series.
- Agent errors per form halved to 6.3%.
- Each chain acted on what its block named. This includes the first send-back since run 9, and it was right.

What did not:
- Both retried tasks capped again. Unres cut the cost from 11.2 MB to 7.2 MB, against a 2 MB bound. The program
  chain chased the loader again.
- The typed-deletion task never reached a `defn` in 40 calls.

## Heap and health

There was no crossing in process 73980. Open faults: `seon.cluster.boot/heap-crossed!` reads 16 occurrences, and
none was written by this process. No error was recorded between 08:26:30 UTC and the end of the run. The G1 old gen
peaked at 1,997 MB of 4,096 (1,230 MB in use afterwards). That includes this lane's JVM reads, the lane's edit-hook
evaluations and lane embed-p3's work on the same JVM. Health stays `:faulted` on open faults older than this process.

## Fixes and filings

- e4cf270bd: grades record the provider-reported model, the turns report windows by commit and model, and the guard
  census refinement (step 1).
- e19694162: intake proofs and tasks (step 2).
- e114c5858: the unresolved-name reply names the require (producer fix).
- This commit:
  - red proof `seon.ai-test/the-provider-build-it-reported-is-kept-from-either-transport` and its task
    `seon.ai/the-model-the-provider-reports-cannot-show-a-swap-behind-a-name`;
  - `seon.turn/a-lone-dropped-delimiter-is-called-tool-call-markup` and
    `seon.db/a-cardinality-many-pull-answers-a-set-where-a-test-reads-a-vector`, each with its existing red proof;
  - retires `seon.mine/the-turns-report-refuses-grades-read-back-from-storage`, mooted by the fork bump b224d0e50.

## Next run

- File the four pre-existing reds of `seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`.
  Then rerun the nsform task with its fifth row in the plan.
- Retry unres from its kept branch's design. It has a 7.2 MB measurement and needs about 5 MB less.
- Intake rule: screen the proofs, every test the plan names, and the namespaces of the owner symbols.
- Kept branches: `:chain-5be74ff65b04`, `:chain-8070210bd188`, `:chain-5ff950ce2f17` and `:chain-13cdf64c0222`.
