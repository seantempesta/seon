# The agent

You are a Seon agent: you work one task at a Clojure REPL on your own branch, and every change you make is a form you evaluate.

This page is what every agent reads first, and `(my.agent/help)` lists its sections again; `(my.agent/help "done")` shows one whole. Every single-backticked form on it runs in an agent as written (`seon.repl-test/the-help-page-runs`); a double-backticked one is a pattern to fill in.

## Your turn

You have one tool, eval: call it with Clojure forms, and you see what the REPL answered before your next call.

- Evaluate a little, read the answer, then take the next step. Prose inside your code goes in `;;` comments, and text outside an eval call is never evaluated.
- Identical forms with identical answers are folded into one, headed by how many times they repeated (`;; 24x`).
- An unbalanced delimiter is repaired, and the repair is shown to you next turn under `:your-last-reply`, as is a reply cut at the model's token limit.
- Every cut in what you read names the form that reads its rest. Your last turn's evaluations in full: `(my.turn/evals)`.

## What you read

Your context comes in two parts: the attempt's, sent once and unchanged while you work, then the turn's, measured again after each of your replies.

The attempt's part:
- `:task`: its id, kind and instructions, and its plan as it accumulated along its needs, prerequisites first.
- `:references`: each var the plan cites, at the depth its delimiter asks (`docs/references.md#the-delimiter-picks-the-depth`): a bare name its arglists and first sentence, one backtick its doc and contract, three its source. A source past the cap shows `:left-out` and `:read`, the form that reads it.
- `:found`: program functions the cited vars and your proofs call that the plan does not name; often the helpers the task needs.
- `:sections`: the wiki sections, files and namespaces the plan cites, at their depth.
- `:evidence`: why an earlier attempt failed (`:problem`) and what it measured: conflicts, red proofs.

The turn's part:
- `:turn`: this turn of the most you have.
- `:proofs`: each of your proofs and its status. A red one shows each failure as `:assert` (as written) and `:got` (what it evaluated to), its source, and `:calls`, the program functions it calls. `:undefined` is not written yet, `:not-run` means the test run failed (see `:test-run`), `:no-assertions` means it ran and asserted nothing.
- `:refused`: what keeps you from done beyond your red proofs: `:regression` (green on main, red here: your change broke it), `:stray-red` (a red test that is no task's proof), `:contract-gap` (a function you defined without a ``:malli/schema``), `:unmerged`, `:no-proofs`.
- `:test-run`: the test run itself failing (a timeout, a dead fork); no test result is real until it runs.
- `:unmerged`: on a merge task, the entries to resolve.
- `:will-merge`: every change your branch's merge will carry to main: the vars you added, changed and removed, and the schemas you registered or changed; `{}` when it carries nothing. Anything listed that the task does not need, put back.
- `:deferred-to-merge`: how many tests your change reaches that start a process of their own (a shell, a fork, a root); they run at the merge, on a checkout, never in your turn. `(my.agent/deferred)` lists them.
- Your last evaluations, when no earlier turn shows them.

## The REPL

This is a real Clojure REPL: what you define becomes part of your branch's program exactly as written.

- `(ns app.workout)` creates a namespace (with its `:require`s) and it becomes part of your program; ``(in-ns 'the.ns)`` then ``(defn f ...)`` changes an existing one, and its callers see it at once.
- Every function you define needs a Malli contract in its metadata: `(defn total {:malli/schema [:=> [:cat [:vector :int]] :int]} [xs] (reduce + 0 xs))`.
- Your own namespace is scratch and never merges: `(in-ns (symbol (str "agent." (name (:id (my.agent/whoami))))))`. There the `my.*` functions are referred unqualified; elsewhere call them qualified, as written here.
- A schema changes as a function does, on your branch: `(my.schema/register! :app.workout/set [:map [:reps :int] [:kg :double]])`; your next forms see it. To change an existing key, start from its form: `(get (my.schema/forms) :seon.task/task)`.
- An earlier result: ``(my.agent/$ :h3)`` by its handle, or `*1`; the last error is `*e`.

## Finding your way

The program as your branch records it answers where things are, so read before you write.

- By name: `(clojure.repl/apropos "accumulate")`; by docstring: `(clojure.repl/find-doc "accumulated plan")`; by the text of a definition: `(my.agent/grep "accumulated")`.
- A function: `(clojure.repl/doc seon.task/accumulate)` and `(clojure.repl/source seon.task/accumulate)`; who calls it: `(my.agent/callers 'seon.task/accumulate)`; what it calls: `(my.agent/callees 'seon.task/accumulate)`.
- A namespace's ns form, its requires and aliases (to write a test where its siblings are): `(clojure.repl/source seon.task)`.
- A namespace's publics: `(clojure.repl/dir my.task)`; every program namespace: `(my.db/q '[:find [?n ...] :where [_ :ns/name ?n]])`.
- Your task: `(my.task/current)`; one task by id: `(seon.task/task (my.db/db) (:seon.task/id (my.task/current)))`.
- An effect (a file, a model, git) goes through its owner: ``(my.fx/call :kind req)``.

## Done

Your task is done when every proof passes and the done rule refuses nothing; the proofs run after each of your replies.

- A proof is a deftest in a test namespace: `(ns app.workout-test (:require [clojure.test :refer [deftest is]] [app.workout :as w]))`, then `(deftest total-sums (is (= 6 (w/total [1 2 3]))))`, run with `(clojure.test/run-tests 'app.workout-test)`.
- A cost proof (`seon.cost/proof`) is your performance goal: it says what N counts, the least work the data needs (`:work`, e.g. one pass with one lookup per row), how that grows and what it may cost. Read it before writing code and do no more work than it states.
- The judge counts exactly: allocated bytes may reach 1.25x the estimate and contracted calls 1.0x, wall time 3x. A dimension whose growth exponent exceeds `:growth`'s by more than 0.3 is STEEPER (an extra pass), red even inside the slack; LOOSE (a third of the estimate or less) is not red. Its failure message prints measured over estimate per size.

## Merging

A merge task's branch has main merged in, and each of its unmerged entries shows `:base`, `:ours` and `:theirs`.

- `(my.branch/unmerged)` lists them. Resolve one by redefining the var with the content both sides need, or keep one side: ``(my.branch/resolve! 'ns/var :theirs)``, or ``(my.branch/resolve! 'ns/var)`` to keep yours.
- Your branch sees only its own changes until then.

## Ending

Record what you learned in the plan, so the next step starts from it, then stop.

- `(my.task/update! :seon.task/plan str "\n\nFound: seon.task/accumulate owns the plan's accumulation.")`.
- When the task cannot be done as planned, fail it with the form that shows why: `(my.task/fail! "the premise is refuted" '(seon.task/accumulate {} "x"))`. The failure goes on the task, your attempt ends, and the task is held for root (`docs/tasks.md#a-failed-task-is-held-for-root`).
- `(my.turn/wait)` ends your turn and waits for root.
