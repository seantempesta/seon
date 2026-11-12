# The agent

You are a Seon agent: you work one task at a Clojure REPL on your own branch, and every change you make is a form you evaluate.

This is the help page, read on demand beside the short system prompt every agent reads first (`seon.doc/system-page`). `(help)` answers the system prompt, then the map of the forms here; `(doc "done")` shows one section of this page whole; `(doc "docs/vector.md#the-model")` shows any wiki section with what it cites and what cites it; ``(doc "docs/<page>.md")`` lists a page's sections. Every single-backticked form here runs in an agent as written (`seon.repl-test/the-template-instructions-and-the-help-page-run`); a double-backticked one is a pattern to fill in.

## Your turn

You have one tool, repl: call it with Clojure forms and read the answer before your next call. (Attached from outside, your tools are the server's: see From outside, below.)

- A reply is its text, one Markdown message to whoever started you, and a call of ``eval`` for each eval: an ``intent`` (a sentence is fine) and ``code``. A call with no intent or no form answers ``:seon.turn/invalid-call`` and runs nothing. Its forms run in order until one throws.
- A reference in your message (``ns/name``, a task ``ns/task-name``, ``docs/page.md#section``) is cited at the depth its delimiter asks (docs/references.md). To read one now: ``(source ns/name)``, ``(doc "docs/page.md#section")``.
- A response is data (``seon.repl/response``; docs/prompt.md lists its keys): the value as Clojure prints it (several top-level forms answer `:forms` and `:value`, the vector of their values), what it wrote, its exception, what it recorded as program, `:redefined` (each existing var whose contract or docstring changed, with the wiki sections citing it), `:repaired`, and its cost. A top-level ``(do ...)`` evaluates each inner form top level, so a definition inside it is program. Its handle, ``result/a3f9c1d2``, holds it: ``(:value result/a3f9c1d2)``.
- An eval's id, ``e<its eid>``, is issued by the store before it runs (``seon.db/take-eid``), unique across branches and processes.
- Code whose delimiters do not balance, where the reader knows the fix, is completed, and a line of prose in it becomes a comment (``seon.reader/forgive``); the result says each change under `:repaired` (``seon.ctx.search/repaired``).
- A reply with no eval call is a message to the user: your turn ends and you wait for their answer; a message sent to you wakes you. Your work finishes when your status shows nothing blocking done: then tell the user what changed, how it is proved, and what would have saved you turns.
- Every cut in what you read names the form that reads the rest. Your last turn's responses: `(my.turn/evals)`.

## Working a task

Read what you will change and the proofs that judge it, make the proofs fail for the right reason, then make them pass.

- When your status shows red proofs with their source, their failures are the reproduction: make the first change.
- For something new, make its namespace first (``(ns …)``), so its proof can refer to it.
- A proof listed with no source is yours to write: a deftest in its function's test namespace (``(tests-reaching 'ns/f)`` lists them; ``(in-ns 'ns.x-test)``, then the deftest, no file), its input built as its neighbours' (their helpers and fixtures, never the live db). Register it, ``(my.task/update! :seon.task/proofs conj 'ns.x-test/a-proof)``, and run it red, ``(prove)``.
- Make it pass by redefining what it needs in its own namespace, keeping contracts, with a cost proof (``seon.cost/proof``) where input grows.
- A short definition, evaluate it whole; a long one, ``(seon.repl/edit! 'ns/f "old text" "new text")`` (Changing a definition, below).
- Check each change at your REPL with the failing assertion's own expression before you prove again.

## Working for a person

When your task is a person's request, what they asked is your plan, and anything they send after is a message. Nothing defined in this session reaches main, so work they want done you launch at once, `(my.task/launch! "what they want")`, and your reply names what you launched; ask first only when what they want is unclear. A question you answer yourself. Your reply ends your turn; their next message wakes you.

- A launched task answers `{:task id}`; you are told when it stops (finished, needs an answer, or died). By default it designs, builds, proves and merges by itself (`seon.template/templates`; docs/tasks.md#the-done-rule).
- Keep what you learn on your task (``(my.task/update! :seon.task/plan str "\n\n...")``) or in your record (docs/agent.md#ctx).
- Show a value rather than describe it: docs/render.md#a-value-draws-itself.

## Designing a task

A design task turns a request into the task the next agent implements: its plan, what the plan cites and its red proofs are that agent's whole starting context.

- Find what the change touches, starting from the request's words: ``(search "a word of it")``; read functions with ``(clojure.repl/source ns/f)``, a namespace with ``(clojure.repl/source ns)``, callers with ``(callers 'ns/f)``. Reproduce what the request says is wrong in one eval; when the output is already right the premise is false: ``(my.task/fail! "why" evidence)``.
- Probe a function with the inputs your proof will assert, not every case; once you can say what changes, write it into the plan, then the proof.
- Cite into the plan as you find, at the depth the implement agent needs: three backticks for each function it changes or must call as it is (its source is shown), one for a doc that explains the area, a bare name for one it only needs to know of: ``(my.task/update! :seon.task/plan str "\n\nWhat changes: ...")``.
- Write the proofs first, as in Working a task, and run them red for the reason the plan gives.
- A plan names forms, never files (the merge writes files): a definition's `defn` or ``(seon.repl/edit! 'ns/f ...)``, a schema ``(my.schema/register! k form)`` starting from ``(get (my.schema/forms) k)``, a doc ``(seon.repl/edit! "docs/x.md#anchor" ...)``. Say each function and what it does then; a new function's defn with its contract and a stub body; a cost proof where input grows.
- Read your page as the implement agent will: cite what it would still search for, remove what it does not need. You are done when an agent with only that page could make the change without exploring.

## From outside

A Claude session attached through the MCP server works a task as a Seon agent does, with the server's tools in place of the eval tool (`docs/server.md`; the tool list gives their arguments):

- `seon_attach` with the task (ns/name) starts your agent on the task's branch and answers its id. A task already worked answers its agent with `:shared true`. `fresh: true` resets the task (a new branch from main, an empty conversation). `from: "a3"` forks agent a3 onto a sibling task `<name>-fork-<n>`; cancel the one you do not want with root's my.root/cancel!.
- The agent you attached is yours: `seon_done`, `seon_eval` and `seon_message` on another session's agent are refused (`:seon.server/held`) unless you also name its task.
- `seon_context` answers what a model is sent now; `wire: true` answers the request body as JSON. It runs no proof.
- `seon_eval` is your REPL, one call one turn: `message-md` (optional) and `evals`, each an `intent` and `code`, all run in order, each answered `:handle :value :out :err :ex`. Agent `root` with plain `code` is root's REPL.
- `seon_prove` runs the tests your change reaches, reusing greens at their digest; `seon_message` tells an agent something; `seon_agents` lists live agents and tasks.
- `seon_done` ends your work: your proofs run, root judges and merges when the done rule refuses nothing, and it answers the verdict. A task that cannot be done as planned: `my.task/fail!` through `seon_eval`, then `seon_done`.

## What you read

Your context is your page and your conversation: the task's part, sent once, then how your work stands, which changes as it does.

Your task's part:
- `:task`: its id, kind, instructions, and its plan accumulated along its needs, prerequisites first.
- `:to-prove` (while one is not written): each proof not written yet and what it should assert.

Each thing the plan cites is a part of its own, at its delimiter's depth (a function's doc or source, a schema's form with the keys it names, a section's lead). The namespaces your work touches are one part: the names your task cites or its proofs call, and `:ns-form` (its requires and aliases, and a line per definition). ``(my.task/data)`` answers the task's whole data.

- **Search first:** ``(search "text")`` finds the best matches over code and wiki sections (tests, tasks and schema keys when ``:kinds`` asks), fusing exact words, meaning and references (docs/vector.md#search): ``[{:kind :name :score :by :relevance :line} ...]``, best first; hits under 40% are only counted, with the call that shows them (``{:min 0.0}``). Options: ``{:kinds #{:section :task} :by #{:text} :ns "seon.merge" :k 20}``. ``(grep "text")`` finds a literal you already know (Small answers, below).
- **Show a person a value:** answer it with ``:seon.render/hiccup``, inline or naming a function you defined, ``{:share 0.72 :seon.render/hiccup 'agent.a1/donut}`` (docs/render.md#a-value-draws-itself).
- **More:** ``(callees 'ns/f)``, ``(callers 'ns/f)``, ``(tests-reaching 'ns/f)``, ``(history 'ns/f {:attrs #{:var/form} :pull [:form/src]})`` (docs/storage.md#history), ``(similar "text")`` (meaning alone), ``(clojure.repl/dir the.ns)``.

The conversation: your replies with their evals and responses, and the messages sent to you, each `{:from :body}` (`:from` the user's name, `"root"`, or an agent's key), each a `:seon.message/*` entity on your branch. It stays in `@ctx` until you remove it.

How your work stands, the `status` part (`(my.task/status)` answers the same), drawn from facts on your branch:
- `:proofs`: each proof's verdict at its test's digest now. A red one shows each failure as `:assert` and `:got`, its source and `:calls`. `:undefined` is not written; `:unknown` has no verdict at its digest: run `(prove)`, never ``clojure.test/run-test-var``, which records nothing; `:not-run` means the test run failed (`:test-run`); `:deferred` is a process test root's gate runs.
- `:refused`: what keeps you from done beyond red proofs (``seon.reconcile/done-explain``), each with a form that acts on it (`:do`): `:regression` (green on main, red here), `:stray-red` (a red test that is no task's proof; if you wrote it as one, its `:do` registers it; `:removed` marks one of main's tests your branch removed), `:contract-gap` (a function without ``:malli/schema``), `:open-problem` (a problem your work made true), `:proof-unknown`, `:unmerged`, `:no-proofs`, and on a design task `:no-design` (your plan is unchanged).
- `:test-run`: the test run itself failing (a dead fork, a namespace that did not load).
- `:without-a-contract`, `:caused`, `:attention` (wiki sections your change made stale: read and probably fix, never a refusal), `:unmerged`, `:unjudged`, and on a task that merges itself `:merge` (`:main-moved`, and `:conflicts`, what you changed too).
- `:also-red-on-main`: a red test that main's merge base holds red too. It is still yours to clear; if your proof's premise already holds there, reproduce it and fail the task with that output.
- The turn line (``seon.render.ui/turn``) shows the namespace your next eval runs in. Moving into a namespace whose own var hides a REPL helper answers `:helper-shadowed`. What your evals changed: `(my.branch/changes)`.

Your tests: `(prove)` runs them; `(deferred)` lists the process tests your change reaches, which run when your status is clear.

## The REPL

An `ns` form adds to its namespace, as Clojure's does: a bare ``(ns x)`` only switches to it, and one with a `:require` adds that require on your branch, keeping the rest.

What you define becomes part of your branch's program exactly as written; your reply's text is recorded with its eval.

- `(ns app.workout)` creates a namespace, part of your program; ``(in-ns 'the.ns)`` then ``(defn f ...)`` changes an existing one, and its callers see it at once.
- Every function needs a Malli contract in its metadata: `(defn total {:malli/schema [:=> [:cat [:vector :int]] :int]} [xs] (reduce + 0 xs))`.
- Your REPL starts in your task's namespace (its `:seon.task/ns`), and your context follows the namespace you are in. In every namespace these work bare: `source`, `doc`, `dir`, `apropos`, `find-doc`, `pst`, `pp`, `pprint`, every `my.agent` and `my.db` function (`grep`, `lines`, `ls`, `search`, `datoms`, ...), `ctx`, and the aliases `d` (datahike.api) and `str` (clojure.string); `my.task`, `my.schema` and `my.branch` stay qualified. A name the namespace defines wins. A program definition resolves as written, so it calls them qualified (`my.agent/grep`). Your scratch namespace, which never merges: `(in-ns (symbol (str "agent." (name (:id (whoami))))))`.
- A schema changes on your branch as a function does: `(my.schema/register! :app.workout/set [:map [:reps :int] [:kg :double]])`. To change an existing key, start from its form: `(get (my.schema/forms) :seon.task/task)`.
- Your branch's program is the forms you evaluate. Writing a file under src or test, or running git, changes the shared checkout, not your program: a file is an effect, through its owner.
- A top-level load-file of a string path evaluates and records the file's forms; one inside another form records nothing.
- An earlier response: ``result/a3f9c1d2`` or ``($ :a3f9c1d2)``, a hash of its answer, kept for your process's life. Keep a value with a `def` or in `ctx`; `*1` is the last value, `*e` the last error, ``(pst)`` prints it.

## Small answers

A response is shown whole, so its size is what you asked for. Each form below is the cheapest Clojure for its question (ticks in millions on the program's vars; `seon.repl-idioms-test` holds the ranking).

- **How many** (`wc -l`): `(count (datoms :aevt :var/name))`, 0.3 M, against 1.3 M for a query.
- **The first few** (`head`): `(->> (datoms :aevt :var/name) (take 5) (map :v))` reads five datoms whatever the db's size; ``(take 5 (q ...))`` computes the whole query first. With a join, ask the query for the limit: `(datahike.api/q {:query '[:find ?n :where [_ :var/name ?n]] :args [(db)] :limit 5})`.
- **Matching lines** (`grep -n`): `(grep "accumulated")`, every line holding it across functions, tests, wiki sections, task plans and schema entries, ``{:at "seon.task/accumulate:3" :kind :var :text "..."}``. Its only options are ``{:in #{:var :test} :ns "seon.task"}`` (`:ns` holds what is under it, so `"seon.task"` holds `seon.task.x`, not `seon.task-test`); `(->> (grep "proofs" {:ns "seon.task"}) (take 5))`, `(count (grep "proofs"))`. A regex, `(grep #"plan|proof")`, reads every text; a string holding regex operators that matches nothing answers `[]` with its regex form. A namespace's ns form is its lines too (`(grep "clojure.test" {:ns "seon.task-test"})`).
- **What a namespace or page holds** (`ls`): `(ls 'seon.task)`, each definition with its doc's first sentence; `(ls "docs/merging.md")`, each heading.
- **Lines n to m** (`sed -n`): `(lines 'seon.task/accumulate 3 6)`, numbered as grep numbers them; a section, a page or a task the same way; `(lines 'seon.task/accumulate)` whole. A namespace's lines are its file as your branch holds it (```seon.source/file-text```): `(lines 'seon.task 380 400)`. The last few: `(take-last 5 (lines 'seon.task/accumulate))`.
- **How big** (`wc`): `(wc (lines 'seon.task/accumulate))`, in tokens.
- **Counts by kind** (`sort | uniq -c`): `(->> (datoms :aevt :var/name) (map (comp namespace :v)) frequencies (sort-by val >) (take 5))`.
- **The shape of a map**: `(keys (my.task/current))`, then sizes: `(->> (update-vals (my.task/current) (comp count pr-str)) (sort-by val >) (take 5))`.
- **Some keys** (`cut`, `jq`): `(select-keys (my.task/current) [:seon.task/id :seon.task/proofs])`; pull only what you need, `(pull [:seon.task/id :seon.task/plan] [:seon.task/id (:seon.task/id (my.task/current))])`, never ``'[*]`` (an entity's `*` can be tens of KB).
- **A big value read in parts**: ``(def vs (vec (datoms :aevt :var/name)))``, then ``(count vs)`` and ``(subvec vs 0 10)``.

## Changing a definition

A definition changes by evaluating its new text: whole, or for a long one by replacing one exact piece of its recorded source. ``(seon.repl/edit! 'demo.sq/square "(* n n)" "(* n n n)")`` finds the old text exactly once in `(source demo.sq/square)` (whitespace counts), puts the new text in its place, and evaluates the whole definition through the one REPL path: recorded as program, its contract armed. The new text is read as your code is (`seon.reader/forgive`, said under `:repaired`). It answers `:edited`, the var and the lines around the change (`:seon.config.agent/edit-context-lines`). It changes nothing and says why (``:seon.repl/edit-refused``) when the old text is `:not-found` (with the nearest lines and where they stop matching, ``:seon.repl/differs``), `:ambiguous` (each candidate's line: give more of it), or the var has `:no-source`. The call that ran `edit!` is the eval the conversation keeps.

**What merges is what the branch records.** A form is program when a definition is its top level, as ``(defn f ...)`` or ``(do (defn f ...))``. One inside a `let` or through `eval` runs in this process only, and its eval says so. A proof with no form on the branch has no result (`:proof-unknown`).

A namespace's ns form is edited by the namespace's name: ``(seon.repl/edit! 'demo.sq "[a.b]" "[a.b] [c.d]")``; an edit states the whole form, so it can remove a require. Old text found in one of its definitions is refused naming that definition (``:seon.repl/held-by``); a new definition is evaluated in its namespace, ``(in-ns 'demo.sq)`` then ``(defn- f ...)``.

Changes that must land together are one edit: ``(seon.repl/edit! 'demo.sq/square [["old 1" "new 1"] ["old 2" "new 2"]])`` finds each old text once, refuses overlapping ones (`:overlap`), and evaluates the definition once (a loop gaining a binding needs its binding vector and each `recur` changed together). A new definition that does not compile is that eval's error, quoting the line it names, and nothing changes. Try a new helper as a `defn-` of its own before the long definition calls it.

Exact text, once, is what file editors converged on (v1's `seon.agent.fs/replace!`, clojure-mcp's `file_edit`); a silent first match changes the wrong place.

## Changing a doc

A doc (a heading and its text, named by its address: the page's path, `#`, the heading's anchor) changes as a definition does: ``(seon.repl/edit! "docs/agent.md#ending" "old text" "new text")`` replaces old text found once in the doc's block (as `(seon.doc/doc-source (seon.agent/db) "docs/agent.md#ending")` shows it) and loads the page again on your branch, so its lead, citations and staleness follow. Parentheses in a doc are text. A cited doc's part names the form that changes it (`:change`). The change is on your branch's store; the page is drawn back byte for byte (```seon.doc/markdown```) and written to its file with the merge.

## Changing a file

``(seon.repl/edit! "/abs/path" "old" "new")`` edits a file on disk the same way: exact text once, or a refusal naming the lines. The file keeps its mode (```seon.fx/write-file```), and ``:seon.text/said`` names the absolute path and says no branch holds it, so no merge carries it.

A file this branch holds changes on the branch instead: a script (``(seon.repl/edit! "bin/system" "old" "new")``, refused `:unreadable` when it fails its interpreter's syntax check) or the config file `resources/seon/config/default.edn` (checked against its schema as a boot reads it; the branch's REPL reads the new value at once). Nothing on disk moves until the merge is written back (docs/source.md#a-program-file). To add a config key: ``(my.schema/register! k form)``, ``(my.schema/register! :seon.config/config (conj (my.schema/forms :seon.config/config) [k {:optional true} k]))``, then the edit (docs/config.md#a-process-holds-its-branchs-config). A schema resource's path answers how an entry changes on the branch. Definitions, docs and paths differ only in where the text is read and what a write does: one multimethod, ```seon.repl/place```, over ``:seon.text/place``, its kind from ```seon.repl/place-kind```.

## ctx

`@ctx` is your record as one map (from another namespace, `@seon.ctx/ctx`): your agent entity pulled by a pattern (your task with the vars it cites and your proofs with their last results, every turn and eval of yours, your newest messages), with `:t`, `:branch` and `:commit` beside them (``seon.ctx/ctx``). A patch from each of your transactions keeps it current, so a read is a deref; `(keys @ctx)` lists what it holds.

- **Its pattern** is Datahike pull syntax at `:seon.ctx/pattern`. Add to it, never replace it: ``(swap! ctx update :seon.ctx/pattern (fnil conj [:seon.db/agent-fact]) <clause>)`` (docs/ctx.md#the-pattern). What your work only measures (a model call's tokens, your pid and memory, an embedding) is on its own entity, never in `@ctx` (docs/ctx.md#the-agents-entity-holds-its-work).
- **To change something,** `swap!` it like any atom. A collection is keyed by each member's id, newest first; each key is an attribute of your entity or its reverse (``:seon.eval/_agent``):
  - your plan: ``(swap! ctx update-in [:seon.agent/task :seon.task/plan] str "\n\nFound: ...")``;
  - its proofs: ``(swap! ctx update-in [:seon.agent/task :seon.task/proofs] conj 'app.x-test/t1)``;
  - a member gone: ``(swap! ctx update :seon.eval/_agent dissoc eid)``; a new one: ``(swap! ctx assoc-in [:seon.message/_to "m-note"] {:seon.message/id "m-note" :seon.message/from "agent" :seon.message/body "..."})``;
  - your own datum: register its key, add it to your pattern, then ``(swap! ctx assoc :app.x/notes [...])``;
  - a search on your page: a member whose query is searched on your branch at each draw. Your request's is under ``(:seon.search/_of (:seon.agent/task @ctx))``; add your own: ``(swap! ctx assoc-in [:seon.search/_of "a1/where-sets-are-logged"] {:seon.search/id "a1/where-sets-are-logged" :seon.search/query "sets a workout has not logged" :seon.search/k 5})``;
  - what you read beside your task: ``(cite! "docs/flow.md?full#idioms")``, ``(uncite! "docs/flow.md#idioms")``;
  - a cited var changes by its definition, never by a `swap!` of its ``:seon.cite/target``.

  A `swap!` is one transaction on your branch, each value checked by its schema, or it is refused and nothing is written: ``:seon.ctx/invalid``, ``:seon.ctx/not-held``, or ``:seon.ctx/basis`` (the function wrote the db itself, or `:t`, `:branch`, `:commit`). A refused swap! is never retried.
- **What arrives:** before your first turn your page whole, as the call ``(my.render/page p)`` and its answer; before each later turn where something moved, ``(my.render/diff p0 p1)``, edits by name (docs/render.md#a-change-is-edits-by-name, docs/render.md#the-render-is-an-eval). Nothing is evaluated again for you.

## Looking at what you pulled

Look before you print: ``(describe @ctx)`` answers each path and what is there, its schema's doc in a line, two levels in (`:seon.config.agent/describe-depth`), never the value whole (a long string as `{:chars n}`, a join as `{:members n :ids [...]}`). ``(describe @ctx path)`` goes in at a path; ``(get-in @ctx path)`` reads one; ``(doc :the/key)`` says what a key means. Paths are keys, never positions.

``(my.db/pulled @ctx)`` gives a pulled entity as its data model, `{:schema :source :clauses :unasked :held-unasked :renders}`: the entity schema naming it and where its pattern came from; the pattern clause by clause (an attribute's `:value`, a join's `:members` count, `:pattern` and `:sub`); what its schema names and the pattern does not ask; what the entity holds that the pattern does not ask; and the renders for a person and for the model. Read a joined member with the join's pattern: ``(my.db/pulled (:seon.agent/task @ctx) (:pattern j))``. A pull reads the one db it is made on, so on your branch a clause main alone keeps has no value. The page's pull view lays out this same map (```seon.render/pulled```).

## Finding your way

The program as your branch records it answers where things are: read before you write.

- By name: `(clojure.repl/apropos "accumulate")`; by docstring: `(clojure.repl/find-doc "accumulated plan")`; by text: `(grep "accumulated")`.
- A function: `(clojure.repl/doc seon.task/accumulate)`, `(clojure.repl/source seon.task/accumulate)`; its callers `(callers 'seon.task/accumulate)`, its tests nearest first with their last results `(tests-reaching 'seon.task/accumulate)`, its callees `(callees 'seon.task/accumulate)`.
- A namespace: its ns form and every definition, a line each, `(clojure.repl/source seon.task)` (```my.agent/source-fn```); which require it and which it requires, `(callers 'seon.task)` and `(callees 'seon.task)`. A name that is no var answers ```my.agent/no-var```: the nearest names, the registry key of that name if any, and the form that lists the namespace.
- A schema: `(my.schema/forms :seon.task/task)`; every key, `(keys (my.schema/forms))`. It changes at the REPL with ``(my.schema/register! k form)``.
- A namespace's definitions in file order: `(doc seon.print.select)`; its publics `(clojure.repl/dir my.task)`; every program namespace `(q '[:find [?n ...] :where [_ :ns/name ?n]])`.
- An error: its `:seon.error/operation` names the function that built it; `(grep "insufficient-floor-budget")` finds where its type is raised.
- Your task: `(my.task/current)`; a task by id: `(seon.task/task (db) (:seon.task/id (my.task/current)))`.
- An effect (a file, a model, git) goes through its owner: ``(my.fx/call :kind req)``.

## Testing

A test runs on the world: `my.test`'s small production-shaped db plus what the test adds (docs/testing.md#testing-in-the-world).

- ``(my.test/with (my.test/world) (my.test/code 'app.x "(ns app.x)\n(defn f [x] (inc x))\n"))`` is a db value; add a page (``my.test/page``), a task (``my.test/task``), remove a var (``my.test/without``), or pass any tx-data or ``(fn [db] tx-data)``.
- A claim about how work grows is a cost proof (``seon.cost/proof``), never a ratio of two readings; ``(my.test/cost #(+ 1 2))`` shows one call's ticks and bytes.
- ``(prove)`` runs the tests your change reaches (Done, below).

## Done

Your task is done when every proof passes and the done rule refuses nothing. A refused end is not retried: the task is held for root (`docs/tasks.md#a-refused-run-is-held-for-root`).

- A proof is a deftest in a test namespace: `(ns app.workout-test (:require [clojure.test :refer [deftest is]] [app.workout :as w]))`, then `(deftest total-sums (is (= 6 (w/total [1 2 3]))))`, judged by ``(prove)``, which records its verdict (clojure.test's own run records nothing).
- A cost proof (`seon.cost/proof`) is your performance goal: what N counts, the least work the data needs (`:work`), how it grows. Do no more work than it states.
- ``(prove)`` runs your proofs and every test your change reaches, reusing each green at its reach digest, and answers one line (`my.agent/prove-summary`); the eval draws what it found (`seon.render.ui/proved`), and the whole run is the value under its handle. ``(prove '#{ns.x-test/a-test})`` runs those tests alone and counts like a bare one. A process test (one that starts an agent, a root, a fork or the binary) does not run there: it is `:deferred`, `:why` naming the process var it reaches. One that is your proof runs on the merge gate's checkout once the rest are green, or at the judge after `seon_done`.
- Each run records its results as `:seon.test/*` facts on your branch (`seon.agent/record-results!`, `seon.proofs/result-tx`); the shared verdict cache means a test green at its digest for anyone is reused by you (docs/merging.md#verdicts-are-a-cache).
- The judge counts exactly (`seon.cost/slack`): bytes may reach 1.25x the estimate, contracted calls 1.0x, ticks 3x. CPU time (`:ns`) is shown ("TIME OVER") but never red. A dimension whose growth exponent exceeds the estimate's by more than `seon.cost/steeper-by` (0.3) is STEEPER, red even inside the slack. The failure prints measured over estimate per size.

A change to a namespace many others require reaches many tests.

## Merging

How merging with main works, and what to do when it conflicts. Main is merged into your branch as it moves, as git rebases: your branch starts again from main and your own transactions since the base are replayed on it. An entity's attribute both you and main changed to your own values is a conflict (`:seon.merge/data-conflict`: `:seon.merge/entity`, `/attribute`, `/base`, `/ours`, `/theirs`); the merge writes nothing, and your status's `:unmerged` names each with its `:seon.merge/resolve`.

- `(my.branch/unmerged)` lists them. Settle them by merging again with resolutions, `[entity attribute]` (the entity by its id or a lookup ref) to the value you keep: ``(my.branch/merge-main! {[[:seon.task/id "app/x"] :seon.task/plan] "the plan both need"})``; `:seon.merge/gone` retracts it.

## Ending

What a task's end leaves: the plan with what was learned, or the task's failure.

- `(my.task/update! :seon.task/plan str "\n\nFound: seon.task/accumulate owns the plan's accumulation.")`.
- When the task cannot be done as planned, fail it with the form that shows why: `(my.task/fail! "the premise is refuted" '(seon.task/accumulate {} "x"))`. The failure goes on the task, your work ends, and the task is held for root (`docs/tasks.md#a-refused-run-is-held-for-root`).
- `(my.turn/wait)` ends your turn and waits for root.
