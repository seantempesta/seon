# Dogfood B: seon.context/a-proof-that-never-ran-says-so (design step), agent a3

Evals used: 25 (e1..e25 files, some with several forms), plus 3 `context` reads, 1 `done`.
Finished the step? Partly. Red proof written and plan rewritten with function citations; no stub defn needed
(no new function); the proof could not be registered as the task's proof (gap 1), and judged :deferred (gap 2).

## Confusions and gaps, in order

1. attach answers `:a3`; `seon-op context :a3` fails with `::a3 works no task: attach one first` (double colon).
   Needed: say the id is passed without the colon. Cost: 1 op call. Fix: accept :a3, or print the id as `a3`/tell how to pass it.
2. eval answers are wrapped in `{:response {:handle ... :text "<pr-str of a map>" ...}}`, with newlines escaped
   (`\n`, `\\\"`); source and docs are unreadable until piped through sed. Only the LAST form's value is returned in :value;
   earlier forms vanish unless wrapped in println (then they show under :out). Cost: ~3 evals to learn. Fix: the prompt should
   say "only the last form's value comes back; print the others", and :out should be returned as raw text, not escaped.
3. The task says "write the proofs as deftests ... run red", but the context's `:proofs []` and `:to-prove {}` show nothing and
   there is no stated way to register a proof on the task. `(my.task/update! :seon.task/proofs conj ...)` is refused by its contract
   (`expected [:enum :seon.task/plan :seon.task/instructions]`). I could not find another way (help "done" never says how a
   proof becomes the task's proof). Cost: 2 evals, unresolved. Fix: the design instructions/help "done" must name the function
   that adds a proof (or say the proof is judged because it reaches what the branch changed), and :proofs in the context should list
   the deftests written on this branch with their status.
4. Where to write the deftest: no help says "evaluate `(in-ns 'seon.context-test)` then `(deftest ...)`" or how to write it in a
   test namespace and be recorded. It worked (`:recorded [seon.context-test/...]` in the answer), which is good, but I had to guess.
   Side effect: in-ns persists across evals; `doc` is then unresolved (`Unable to resolve symbol: doc`; in the default ns it is
   referred), costing 1 eval. Fix: help should show the in-ns + deftest recipe and say the ns persists; use `(in-ns 'user)` to return.
5. The test helpers I needed (`db-with-program`, `task-f`) are private to seon.context-test; I found them by reading
   `an-unknown-proof-says-what-to-write` (found via `my.agent/callers 'seon.context/current-state`). That worked well: callers
   returning tests is the best discovery tool; the prompt/help should say "find a sibling test via callers and copy its setup".
6. Running my proof with `(clojure.test/run-tests 'seon.context-test)` ran the whole namespace: 36 tests, 7.3 s, 797M ticks,
   3 GB allocated. A single-test form was not given by any help. The task instructions said "run red"; the only thing named in
   help "done" is `(clojure.test/run-tests 'ns)`, which is the expensive form. Fix: show `(clojure.test/test-vars [#'ns/test])`.
7. `(my.agent/prove)` answered `:deferred [seon.context-test/a-proof-that-never-ran-says-so] :ran 0 :selected []`: my fresh
   test is classed a PROCESS test and never runs on a turn or at the judge (`:when-asked 1`, `:root-runs`). I could not tell why
   (test-plan docs say :process = tests reaching a process; this test reaches seon.context/current-state and a test-db). The
   task is about exactly this: a proof that did not run. The context says only `:will-merge {:added [...]}` and `:tests ...` with
   no sign the proof is red, and `(my.task/status)` still says `:seon.turn/not-observed` after my eval (it is observed only by a
   turn, and my evals are not "turns" of the read kind); `context` read did not show my proof under :proofs either.
   Fix: status should answer from the last observe or run it; `:tests :when-asked 1` should name the test and the reason it is a
   process test (which proc it reaches).
8. Root cause location was findable but long: status -> turn-state (`st` falls to `:no-assertions` when no result) -> `observed`/`observe`
   (`results` from `proofs/results`, "A test with no assertion has no entry (a proof missing here is :none)" in its docstring, a
   third word) -> `agent/prove!`. 8 evals. A docstring in turn-state says "no result: not written yet, the run failed as a whole, or it asserted nothing" as a comment, not in the doc.
   Surprise: the test run (`my.agent/prove`) has `:ran` as a count, no list of finished tests, so "ran with no assertions" and "never ran"
   cannot be told apart from the run alone; the implement step needs a data change in the test run, not only the status.
9. Docstring/ comment vocabulary drift: `:none` (proofs/results doc), `:no-assertions`, `:not-run`, `:undefined`, `:unfinished`.
10. The context's `:refused [{:reason :no-design ...}]` was clear and useful. The prompt's ask for prose before each eval was fine.

## Errors/surprises from the REPL
- `(my.task/update! :seon.task/proofs conj 'x)` -> contract error, expected [:enum :seon.task/plan :seon.task/instructions].
- `(doc ...)` after `(in-ns 'seon.context-test)` -> Unable to resolve symbol: doc.
- multi-form eval returns only the last value.

## Top 5 gaps (by cost)
1. No stated way to register a proof on a design task (`update!` refuses :seon.task/proofs; context `:proofs []`). Fix: name the function in the instructions/help "done", and show written proofs with status in the context.
2. My new test was silently :deferred as a process test (`:ran 0`), so "run red" through the judge/turn path was impossible; no reason given. Fix: `:tests` names deferred tests and the process they reach; help says how to run one test directly.
3. Eval output is an escaped string inside a map, and only the last form's value returns; reading source needed sed and println. Fix: return :out raw, say so in the prompt.
4. `(my.task/status)` says not-observed after evals; the form the screen/prompt names is unusable until a turn. Fix: run observe on first call, or the prompt says it works only after a model turn.
5. `run-tests` on a namespace is the only run form shown (7 s, 3 GB); Fix: show `clojure.test/test-vars`; and the `seon-op` agent id passing (`a3` not `:a3`).
