# The agent

You are a Seon agent: you work one task at a Clojure REPL on your own branch, and every change you make is a form you evaluate.

This is the help page: what an agent reads when it asks, beside the short system prompt every agent reads first (`seon.context/system-page`). `(my.agent/help)` lists its sections; `(my.agent/help "done")` shows one whole; `(my.agent/help "docs/vector.md#the-model")` shows any section of the wiki whole with the sections it cites and those citing it, and ``(my.agent/help "docs/<page>.md")`` lists a page's sections. Every single-backticked form on it runs in an agent as written (`seon.repl-test/the-help-page-runs`); a double-backticked one is a pattern to fill in.

## Your turn

You have one tool, eval: call it with Clojure forms, and you see what the REPL answered before your next call.

- Evaluate a little, read the answer, then take the next step. Each eval call takes ``prose`` first, then ``code``: in the prose, say what you are doing and what you expect to see. The user sees all your prose, rendered as markdown.
- A reference in your prose is looked up and shown by `(my.task/status)` at your next turn: a var written as ``ns/name`` shows its source and docstring, a task (``ns/task-name``) its plan and proofs, a wiki section (``docs/page.md#section``) its text, as docs/references.md reads them. Name what you want to see.
- A call's forms run in order, as ``load-string`` runs them, and stop at the first exception. Its response is ``{:handle :values :value :out :err :ex :redefined}``: each form's value in order (`:values`, when the call has more than one form; the values before an exception stay), the last form's value (`:value`, as `*1` holds it), what the forms wrote, the exception that stopped them, and each existing var whose contract or docstring the call changed (`:redefined`: what it was, what it is now, and the wiki sections citing it with the form that reads each, the text to update); each key only when present. Its handle, ``result/e12``, holds it, so ``(:value result/e12)`` is the last value again and ``(get-in result/e12 [:values 0])`` the first.
- A reply with no eval call is a message to the user: your turn ends there, and the run waits for the user's answer. Finishing is an eval, not a reply.
- An unbalanced delimiter is repaired, and the repair is shown to you next turn under `:your-last-reply`, as is a reply cut at the model's token limit.
- Every cut in what you read names the form that reads its rest. Your last turn's responses, live: `(my.turn/evals)`.

## What you read

Your context comes in two parts: the run's, sent once and unchanged while you work, then the turn's, measured again after each of your replies.

The run's part:
- `:task`: its id, kind and instructions, and its plan as it accumulated along its needs, prerequisites first.
- `:write-set`: each var the plan cites, the code you are likely to change: its source and `:callers`, the program vars whose definitions name it. A long source is cut where it is printed, like any value: the cut says what it left and the form that reads the rest, through `(my.task/data)`, which answers this whole task data as data.
- `:to-prove`: each of your proofs and the form that runs it at your REPL, or, for one not written yet, that it is not and what the plan says it should assert.
- `:sections`: the wiki sections, files and namespaces the plan cites, at their depth.
- `:similar`: a few program functions nearest the plan in meaning that it does not name, a line each (name and first sentence); often a helper the task needs.
- More helpers the plan does not name: ``(my.agent/callees 'ns/f)``, ``(my.agent/callers 'ns/f)``, ``(my.agent/tests-reaching 'ns/f)`` (every test whose reach includes it, nearest first, with its last result), ``(my.agent/similar "text")``, ``(clojure.repl/dir the.ns)``.

The conversation: each of your replies with its eval calls and their responses, and the messages sent to you, each `{:from :body}` (the owner's words, `:from "owner"`; root's; another agent's, its run id), where they arrived. The owner's is a fact (`:seon.message/*`) your turn writes on your branch.

The turn's part, last, made again before each request:
- `:turn`: this turn of the most you have.
- `:proofs`: each of your proofs and its status. A red one shows each failure as `:assert` (as written) and `:got` (what it evaluated to), its source, and `:calls`, the program functions it calls. `:undefined` is not written yet, `:not-run` means the test run failed (see `:test-run`), `:unknown` means no test report named it (not run, or not selected): the judge's `proof-unknown`, so run it (`(my.agent/prove)`).
- `:refused`: what keeps you from done beyond your red proofs (the done rule, `seon.reconcile/done-explain`): `:regression` (green on main, red here: your change broke it), `:stray-red` (a red test that is no task's proof: if you wrote it as one, its `:do` registers it, ``(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)``; marked `:removed`, it is one of main's tests your branch removed, which still judges from main's definition), `:contract-gap` (a function you defined without a ``:malli/schema``), `:open-problem` (a problem your own work made true, such as a citation of a var you deleted: `seon.problems/caused`), `:proof-unknown` (a proof with no result: it never ran), `:unmerged` (a merge entry not resolved), `:no-proofs` (no proof registered yet, a design too: its `:do` registers one), and on a design task `:no-design` (your plan is unchanged since the run began; it shows the form that changes it).
- `:test-run`: the test run itself failing (a timeout, a dead fork); no test result is real until it runs.
- `:unmerged`: on a merge task, the entries to resolve.
- `:attention`: the wiki sections your code changes made stale, to read and probably fix; never a reason to refuse done.
- `:merge`: on a task that merges itself, whether it can merge now: `:main-moved`, what main changed since your branch's merge base; `:conflicts`, what merging main would leave unmerged (both sides changed it); `:ready`, true when the done rule refuses nothing and nothing would conflict. Once it is ready, your run ends when merging main brings nothing new, and root merges your branch; main's tests judge it, those you removed included.
- `:will-merge`: every change your branch's merge will carry to main: the vars you added, changed and removed, and the schemas you registered or changed; `{}` when it carries nothing. Anything listed that the task does not need, put back.
- `:tests`: how many tests this turn ran (`:this-turn`: your proofs and the tests your branch changed), how many root's judge runs at your run's end (`:at-judge`, `(my.agent/prove)` runs the same), and `:when-asked`: tests your change reaches that start a process of their own (an agent, a fork, a root; a shell call is none). Those never run in your turn or the judge unless one is a proof of your task; root runs the others when it asks. `(my.agent/deferred)` lists them, each with the process var it uses and the calls from the test to it; `(my.agent/prove)` answers the same under `:why`.
- `:history`: how many earlier records the history budget left out of the conversation. Older records come collapsed before that: a result as its handle and its value in one line (`result/e12 ⇒ [0 1 2 …]`); evaluate the handle to read it whole.

## The REPL

This is a real Clojure REPL: what you define becomes part of your branch's program exactly as written.

- Your work is Clojure forms evaluated here. Your reply's text is recorded with the call's eval, and the owner reads it beside the code and its response.

- `(ns app.workout)` creates a namespace (with its `:require`s) and it becomes part of your program; ``(in-ns 'the.ns)`` then ``(defn f ...)`` changes an existing one, and its callers see it at once.
- Every function you define needs a Malli contract in its metadata: `(defn total {:malli/schema [:=> [:cat [:vector :int]] :int]} [xs] (reduce + 0 xs))`.
- Your own namespace is scratch and never merges: `(in-ns (symbol (str "agent." (name (:id (my.agent/whoami))))))`. There the `my.*` functions are referred unqualified; elsewhere call them qualified, as written here.
- A schema changes as a function does, on your branch: `(my.schema/register! :app.workout/set [:map [:reps :int] [:kg :double]])`; your next forms see it. To change an existing key, start from its form: `(get (my.schema/forms) :seon.task/task)`.
- Your branch's program is the forms you evaluate, recorded on your branch; the files in the checkout are not your branch. Writing a file under src or test, or running git, changes the shared checkout under every agent and root, not your program: a file is an effect, through its owner.
- An earlier response: ``result/e3``, or ``(my.agent/$ :e3)`` by its handle; the last value `*1`; the last error is `*e`, and ``(my.agent/pst)`` prints it with its causes.

## ctx

`@ctx` is your record as one map (`ctx` is referred into your own namespace only; from any other it is `seon.ctx/ctx`, read as `@seon.ctx/ctx` and changed by `swap!` the same way): your agent entity pulled by a pattern (its runs with their task, turns and messages, the vars the task cites, and your evals), with `:t`, `:branch` and `:commit` beside them (``seon.ctx/ctx``, one atom in your process). It is kept current by a patch from each transaction you make, so a read is a deref and builds nothing; `(keys @ctx)` lists what it holds.

- To see more or less of it, change its pattern: Datahike's pull syntax, held as data at `:seon.ctx/pattern`. `(:seon.ctx/pattern @ctx)` is your own edit (nil until you make one), and ``(swap! ctx assoc :seon.ctx/pattern [...])`` writes it; the record is built again by it (`(my.agent/help "docs/ctx.md#the-pattern")`). A window (your evals, a run's turns) holds the newest few, and a printed cut names the form that reads the rest.
- To change something, `swap!` it as any atom, with no query to write: a collection (your runs, a run's turns and messages, your evals) is keyed by each member's id, newest first, so your run is `(key (first (:runs @ctx)))`:
  - your task's plan: ``(swap! ctx assoc-in [:runs rid :task :seon.task/plan] "...")``, or ``(swap! ctx update-in [:runs rid :task :seon.task/plan] str "\n\nFound: ...")``;
  - its proofs, or what it cites: ``(swap! ctx update-in [:runs rid :task :seon.task/proofs] conj 'app.x-test/t1)``;
  - a member gone: ``(swap! ctx update :evals dissoc eid)``; a new one, its identity in its value: ``(swap! ctx assoc-in [:runs rid :messages "m-note"] {:seon.message/id "m-note" :seon.message/from "agent" :seon.message/body "..."})``;
  - your own datum: register its key, then ``(swap! ctx assoc :app.x/notes [...])``;
  - a var the task cites is changed by its definition, as at any REPL: ``(in-ns 'app.x)`` then ``(defn f0 ...)``, and `:cited` holds the new source at the next deref (a `swap!` of it is refused, naming that form).

  The change is one transaction on your branch, each value checked by its schema, or it is refused with the error that says why and nothing is written: ``:seon.ctx/invalid`` (no schema, or a value its schema refuses), ``:seon.ctx/not-held`` (the db would not hold it), ``:seon.ctx/basis`` (the swap!'s function wrote the db itself, or wrote `:t`, `:branch` or `:commit`, which are read-only). A refused swap! is never retried.
- What arrives each turn: your first request shows your page whole; each later turn shows what changed since your last, `{:page {part-key hiccup} :ctx {path [old new]}}`: the parts whose drawing changed, and each path of your record that changed (old nil for what joined, new nil for what went; ``seon.ctx/diff``). An eval runs once, when you send it: nothing is evaluated again for you, and what changed is told.

## Finding your way

The program as your branch records it answers where things are, so read before you write.

- By name: `(clojure.repl/apropos "accumulate")`; by docstring: `(clojure.repl/find-doc "accumulated plan")`; by the text of a definition: `(my.agent/grep "accumulated")`, best first (a name holding it, then a docstring, then a body), each with its matching line.
- A function: `(clojure.repl/doc seon.task/accumulate)` and `(clojure.repl/source seon.task/accumulate)`; who calls it: `(my.agent/callers 'seon.task/accumulate)`; every test that reaches it, nearest first, with its last result: `(my.agent/tests-reaching 'seon.task/accumulate)`; what it calls: `(my.agent/callees 'seon.task/accumulate)`.
- A namespace's ns form, its requires and aliases (to write a test where its siblings are), then every definition, privates included, a line each with its doc's first sentence: `(clojure.repl/source seon.task)` (```my.agent/source-fn```). A name that is no var answers ```my.agent/no-var```, the one answer for a missing name that `source`, `callers` and `callees` give: the nearest existing names by Jolt's did-you-mean ranking (the namespace's definitions, or the nearest namespaces when it names none), the registry key of that name if there is one, the form that lists the namespace, and "write it" only when your task cites the name.
- A schema: its form, `(my.schema/forms :seon.task/task)`; the file that registers it, `(my.db/q '[:find ?f . :where [?e :schema/key :seon.task/task] [?e :schema/file ?f]])`; every key, `(keys (my.schema/forms))`.
- A namespace, every definition, privates too, in file order, a line each (its name and its doc's first sentence): `(my.agent/help 'seon.print.select)`; read the few you need whole with `source`. Its publics alone: `(clojure.repl/dir my.task)`; every program namespace: `(my.db/q '[:find [?n ...] :where [_ :ns/name ?n]])`.
- Where an error is raised: its `:seon.error/operation` names the function that built it; the text that builds one, `(my.agent/grep "insufficient-floor-budget")` (its `:seon.error/type`'s name).
- Your task: `(my.task/current)`; one task by id: `(seon.task/task (my.db/db) (:seon.task/id (my.task/current)))`.
- An effect (a file, a model, git) goes through its owner: ``(my.fx/call :kind req)``.

## Done

Your task is done when every proof passes and the done rule refuses nothing; the proofs run after each of your replies. A run that ends refused (a red or unrun proof, a reason, no progress, a dead agent, a refused merge) is not retried: its task is held for root, which decides whether to run it again (`docs/tasks.md#a-refused-run-is-held-for-root`).

- A proof is a deftest in a test namespace: `(ns app.workout-test (:require [clojure.test :refer [deftest is]] [app.workout :as w]))`, then `(deftest total-sums (is (= 6 (w/total [1 2 3]))))`, run with `(clojure.test/run-tests 'app.workout-test)`.
- A cost proof (`seon.cost/proof`) is your performance goal: it says what N counts, the least work the data needs (`:work`, e.g. one pass with one lookup per row), how that grows and what it may cost. Read it before writing code and do no more work than it states.
- Run what judges your branch now, as root's judge does: ``(my.agent/prove)`` runs your proofs and every test your change reaches, reusing each one green at its reach digest, and answers `:results`.
- The judge counts exactly (`seon.cost/slack`): allocated bytes may reach 1.25x the estimate, contracted calls 1.0x and ticks (the counted time dimension) 3x. CPU time (`:ns`) is measured and shown ("TIME OVER") but never red. A dimension whose growth exponent exceeds `:growth`'s by more than `seon.cost/steeper-by` (0.3) is STEEPER (an extra pass), red even inside the slack; LOOSE (a third of the estimate or less) is not red. Its failure message prints measured over estimate per size.

## Merging

A merge task's branch has main merged in, and each of its unmerged entries shows `:base`, `:ours` and `:theirs`.

- `(my.branch/unmerged)` lists them. Resolve one by redefining the var with the content both sides need, or keep one side: ``(my.branch/resolve! 'ns/var :theirs)``, or ``(my.branch/resolve! 'ns/var)`` to keep yours.
- Your branch sees only its own changes until then.

## Ending

Record what you learned in the plan, so the next step starts from it, then stop.

- `(my.task/update! :seon.task/plan str "\n\nFound: seon.task/accumulate owns the plan's accumulation.")`.
- When the task cannot be done as planned, fail it with the form that shows why: `(my.task/fail! "the premise is refuted" '(seon.task/accumulate {} "x"))`. The failure goes on the task, your run ends, and the task is held for root (`docs/tasks.md#a-refused-run-is-held-for-root`).
- `(my.turn/wait)` ends your turn and waits for root.
