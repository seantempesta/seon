# ctx: the agent's entity, pulled as one map

An agent reads its own entity with `@ctx`: its agent entity pulled by a pattern (its task with the vars it cites, its model calls, its evals and its messages), each key an attribute, with `:t`, `:branch` and `:commit` beside them. It is a plain Clojure atom (```seon.ctx/ctx```), kept current as the agent works, so a read is a deref and nothing is built for it.

## The pattern

The map is the agent's entity pulled by a pattern: Datahike's pull syntax, held as data (``seon.render/pattern`` reads it, for the walk and for the page alike). An entity schema declares its pattern in its `:seon.ctx/pattern` property; an agent edits its own by `swap!`ing `:seon.ctx/pattern` on ctx, which is an attribute of its entity, and the pull is built again from it. Nothing else says what ctx holds.

**Which schema names an entity** is the most specific one its identity attributes name (```seon.render/entity-schema```). A schema extends another with Malli's `[:and {props} :parent [:map ...]]` (``seon.db/parents``); an attribute belongs to the one schema that lists it itself (``seon.db/own-keys``), and a property a schema leaves out is its parent's (```seon.render/schema-property```: the pattern, the render, the layout). Identities naming schemas none of which extends the others are an error naming them (`:seon.render/no-one-schema`).

What a pattern may hold:
- **Attributes**, each as itself: a cardinality-many one a set, a [plain value](storage.md#plain-values) as itself. `'*` is root's pattern alone; every other pattern names what its readers read.
- **`{attr sub}` joins.** `sub` is a pattern, or an entity schema's name standing for its declared pattern (```seon.render/expanded```, answered by ```seon.render/entity-pattern```; the stored pattern keeps the name). A schema's name may also stand among a pattern's clauses. `sub` may be a depth, a positive integer or a config key (``:seon.config.render/agent-tree-depth``): the enclosing pattern again, one less each level. A depth join's `:seon.ctx/below` option says what its last level asks, so nothing is cut silently; `:seon.ctx/below` on any other join is refused. A join's key is its attribute (``:seon.eval/_agent``, ``:seon.model-call/_agent``, ``:seon.message/_to``, `:seon.agent/task`); a ref join never takes `:as` ([things relate by refs](data-modeling.md#things-relate-by-refs), `seon.db/implied-pattern`).
- **Collections.** A reverse ref or a cardinality-many join is a map of its members by their ids, greatest first, so time-ordered ids (evals, model calls) read newest first; a member keeps its own name in its value.
- **Windows**, `:limit n :reverse true`: the newest n. Time-ordered ids are read by [our Datahike's `:reverse`](forks.md#datahike), stopping at n; content-id members (messages, forms) are read whole and the newest n kept. A limit may name a config key (``:seon.config.ctx/window``, ``:seon.config.ctx/callers``). A window's join carries its count in the db as metadata (`(-> @ctx :evals meta :seon.ctx/count)`). A patch moves a window by its transaction's datoms (``seon.ctx/window-moves``), so a member joining costs that member.
- **`:tx`** names a key under which a transaction entity is held (`:db/id`, `:db/txInstant`, `:seon.tx/branch`). On a join each member holds the transaction that wrote its link; on an attribute, its datom's; on a cardinality-many attribute, a vector in write order with each value under `:v`. It is read off the datoms the node is built from (```seon.ctx/own```, ```seon.ctx/linked```), never stored, and a `swap!` that edits it is refused. The agent's pattern asks it as `:at` on its three conversation joins. Order by time, ties broken by transaction id (owner, 2026-11-11: "use time for ordering not TX-ids"): `(sort-by (juxt (comp :db/txInstant :at) (comp :db/id :at)) xs)`. Datahike makes instants strictly increase on one branch; a merge keeps each fact's own transaction. The forms it serves are in ```seon.ctx-test/a-pattern-names-the-key-a-transaction-is-held-under```.
- **`:by` joins by name** (ctx's option beside pull's): forward, each value a lookup ref (`{[:seon.task/proofs :by :var/name :as :proved] […]}`); reverse, the entities whose attribute holds this one's name (a var's `:callers`). A join by value keeps its `:as`.

An entity is held whole at every path the pattern pulls it, each by that path's sub-pattern, as `d/pull` holds it (```seon.ctx/node```). A render that reads a new key of a join must still place what it placed when the key is missing, since an agent's own pattern may predate it (`seon.ctx-test/an-own-pattern-written-before-a-join-asked-its-transaction-still-places-its-messages`).

A task's pull (`:seon.task/task-fact`'s pattern) holds what its work needs:
- `:proved`, its proofs, each with its `:var/form` and the vars it calls (`:form/refs`);
- ``:seon.cite/_of``, its cites, each holding only its target's identity ([an entity cites](references.md#an-entity-cites));
- `:seon.task/needs`, one level deep, each with its `:proved` and cites.

An agent's errors are no join: they reach its context from the page's problems part (`seon.problems/own`, ```seon.render.ui/problems-model```). The agent's pull joins its own ``:seon.cite/_of`` too (``my.agent/cite!``). Its task is `:seon.agent/task` and its turn its own number ([turn](turn.md#the-turn-is-a-number)), 0 before its first turn.

Every render of ctx reads what this declaration holds ([render](render.md#the-page)).

## What the scorer reads from a pulled node

The scorer (```seon.ctx.search/relevance```) ranks the nodes the pull holds, and the page shows the ranked set. The pull carries stored facts only, each node's `:db/id` and its schema:
- *test result*: `:seon.test/status` (`:green` `:red` `:flaky` `:deferred`), failures, ms and digest, on the test's var entity;
- *eval*: `:seon.eval/id`, `by`, `code`, `response`; *var and form*: `:var/name`, `:var/form`, `:form/src`, `:form/refs`; *task*: plan, proofs, cites, needs, failure, evidence; *section*: address, heading, text.

What the scorer derives (is a test a proof, is a var cited, did it change on this branch, ```seon.merge/divergence```; does a test reach it, ```seon.program```) comes from the pull and the producers, never from a stored field. It is pool-relative, so it is called once with every candidate. An agent's own pull is never windowed; root's windows its copies of other processes' data (``:seon.config.ctx/window``), and a join through the program is bounded newest first (`:callers`, ``:seon.config.ctx/callers``).

## Kept by the change, never rebuilt

``seon.ctx/build`` walks the pattern once, when the agent's state first holds its db (a spawn, a resume, a clone), and ``seon.ctx/patch`` keeps it from each transaction's datoms (``seon.agent/transact!`` keeps them with the db they made). The walk's index knows where each entity is held, by which pattern, and which `[attribute value]` each join watches; a patch rebuilds each touched entity at each path whose pattern reads what touched it and shares everything else as the same object. A move with no transaction (a merge of main, a resume) is patched from the datoms the two values differ by. A pattern change builds anew, sharing every unchanged subtree.

A patch costs its change, flat in the session's size (`seon.ctx-test/the-patch-costs-the-change-not-the-session`, `a-window-moves-by-its-change`, `a-lookups-patch-costs-its-change`, `a-callers-window-costs-its-forms`). After each of 150 random changes the patched pull equals a fresh build, and every path no change reached is `identical?` to before (`seon.ctx-test/ctx-is-the-record-after-every-transaction`), so a render's memo is a pointer test. ``seon.ctx/changes`` is the one diff of two values, walking members in step and skipping identical ones (`seon.ctx-test/changes-step-costs-are-current`). The environment (`:t`, `:branch`, `:commit`) is new each transaction, so nothing caches on the root map.

``seon.ctx/diff`` answers what changed of the pull over a turn, `{path [old new]}`, from ``seon.ctx/advance``, which patches the start's pull by the net datoms between the two dbs; it costs the changed paths, never the pull (`seon.ctx-test/a-turns-diff-is-what-changed-between-its-ts`).

**ctx is the db's data, nothing derived** (owner, 2026-10-26: "no we don't store derived data in the db or the ctx atom"): what a view needs beyond the pull is a render over the db, memoized by what it read ([derived data](data-modeling.md#derived-data-is-a-cache-never-relied-on)).

**Any entity, any branch.** ``seon.ctx/build`` takes any entity id (an agent's, root's, the user's, a task's), and ``seon.ctx/follow`` patches a pull from main's commits as readers receive them (`seon.ctx-test/any-entitys-record-follows-mains-commits`).

The task's part is a component over ctx and the db ([render](render.md#components)): ```seon.render.ui/task-view``` renders the plan, ``seon.ctx.status/task-data`` (``my.task/data``) holds the write set, and ``seon.ctx/cited`` gives a cited var's source and callers. How the work stands is ```seon.ctx.status/status``` of the branch's facts, rendered, never held. Messages the turn takes are written on the branch (``seon.turn/deliver!``) and sit under ``:seon.message/_to``.

## What the model reads, and what the page renders

`@ctx` carries `{:seon.render/schema :seon.ctx/ctx}` as metadata. That schema declares a page render, ```seon.render.ui/ctx``` (a line per key, each by the render its key's schema declares, else the printer's structure), and none for the model, so the model reads `@ctx` through the one printer like any value.

At its first point the model is shown its ctx whole, as data, before its page: `(my.render/ctx p)` and its result, an eval Seon makes from the first call's basis (```seon.ctx.wire/ctx-at```, ```seon.ctx.wire/page-diff-msgs```). It is exactly the live `@ctx` at that point, its own pattern whole, never a filtered copy (`seon.agent-test/the-record-shown-is-the-live-ctx`).

An eval of `@ctx` is kept as its basis (`:branch`, `:t`), never as a copy: only the value `identical?` to the atom's names it (``seon.repl/record-details``). ``seon.ctx.wire/basis-value`` rebuilds it as of t, and ``seon.ctx.wire/tool-text`` and ``seon.render.ui/evaluation`` render it.

A key whose schema the branch registered or changed is rendered by the agent, since its render may exist only there: the render proc asks the agent for it while the page is viewed (``seon.render.proc/ask-parts``, an `{:op :render}` request answered by ``seon.repl/agent-step``). An agent that is gone leaves the key as its structure, saying so.

## Adding a datum and its render

An agent adds to its pull, and to what it sees of it, with five evals (`seon.ctx-test/an-agent-adds-a-datum-and-its-render`):

```clojure
(my.schema/register! :lab.ctx/steps '[:vector {:seon.render/hiccup lab.ui/steps} :string])
(ns lab.ui) (defn steps "The plan's steps, numbered." {:malli/schema [:=> [:cat :map [:vector :string]] :any]} [_ v] [:ol (for [s v] [:li s])]) (in-ns 'agent.a1)
;; a pull holds only the attributes its pattern names: name the key in yours
(swap! ctx update :seon.ctx/pattern (fnil conj [:seon.db/agent-fact]) :lab.ctx/steps)
(my.db/transact! [{:seon.agent/key (:seon.agent/key @ctx) :lab.ctx/steps ["read the plan" "write the test"]}])
@ctx
```

A registered key is an attribute at once (``my.schema/registration``). Its block renders by `lab.ui/steps`, in the agent, unmerged. What a render is handed: [components](render.md#what-a-render-is-handed); a key's render gets the entity index and the key's value and reads the pull as `@seon.ctx/ctx`.

**Seeing a render you are changing.** A key you added is a part of your page (```seon.render.ui/ctx-keys```) and of your model's request. Give a key of yours a render that calls the one you are changing on a node of your pull, and each request after holds what it draws now.

**Your own render, or the shared one.** Try a new rendering as a key of your own first; redefining a shared render on your branch ([reactive renders](render.md#reactive-renders)) changes what every agent reads once it merges. A render is found by its contract:

```clojure
(d/q '[:find ?n ?c :where [?v :var/contract ?c] [?v :var/name ?n] [(clojure.string/includes? ?c ":seon.ctx/ctx")]] (seon.agent/db))
```

## Changing it: swap!

An agent changes its pull as it changes any atom: `(swap! ctx assoc :lab.ctx/steps [...])`, `(swap! ctx update :seon.eval/_agent dissoc 1753)`, `(swap! ctx assoc-in [:seon.agent/task :seon.task/plan] "...")`. The atom's validator, ``seon.ctx/write!``, turns the change into one transaction on the branch: attributes set or retracted, join members added (keyed by identity), removed (the ref retracted, the entity kept, so a removed message is not delivered again and can be brought back) or changed. An edit is of an entity by `:db/id`, at any path it sits at; two different edits of one entity in one swap! are refused naming both paths. Every value is checked by its schema, a dry run (`d/with`) checks the db would hold what the swap! says, then it is applied, and the pull is the db's. Finding the change costs the change (`seon.ctx-test/a-write-costs-its-change-not-the-record`).

Refused, nothing written (`seon.ctx-test/a-swap-writes-the-record`):
- ``:seon.ctx/invalid``: a key with no schema, a value its schema refuses, a path through a join to no member, or a pattern that drops a join its role requires (`:seon.ctx/required` on the schema; ```seon.ctx/lost-required```). A pattern is the agent's own data, added to and never replaced; `:seon.ctx/pattern`'s schema description says so beside it ([an entity may name its own render](render.md#an-entity-may-name-its-own-render));
- ``:seon.ctx/not-held``: the db would not hold what the swap! says;
- ``:seon.ctx/basis``: the map's `:t`, `:branch` or `:commit` is not the pull's (the function wrote the db, refused once and never retried), or the value is no map.

The environment is read-only; everything else is writable, and history keeps what a write retracts.

## Deleting, compacting and resetting are changes of data

Deleting, editing, compacting or forgetting what an agent has read is a `swap!` on `@ctx`, and its next request is rendered from the pull as it then is, so the provider's cache breaks only where history changed: to compact, replace the first entity of a range with a summary and unlink the rest. Resetting to a point is a new branch from that commit under the same key ([an agent goes back to an eval](turn.md#an-agent-goes-back-to-an-eval)). Only unlinking has a test; page controls for the rest are in [the plan](plan.md#now).

**The page is the agent's to change.** The page is the agent schema's `:seon.render/hiccup` (```seon.render.ui/agent```), composed of components ([render](render.md#components)); an agent overrides it or any part by registering its own on its branch, and its model reads the same output (```seon.repl/render-ctx-hiccup```).

## The agent's entity holds its work

The agent's entity holds facts about its work; telemetry and derived data live on their own entities pointing at what they describe, so the agent's ctx moves only when its work does (`seon.ctx-test/an-agents-record-holds-only-its-work`). A turn is a number, not an entity ([turn](turn.md#the-turn-is-a-number)).
- **Model calls** (`:seon.model-call/*`, one a turn, id the digest of the agent's key and turn number, ``seon.lib/call-id``): the pull names the model's words and what places them (`id`, `n`, `origin`, `reply`, `reasoning`, `finish`, `truncated`, `error`). Tokens, cache counts, the basis and the sent hash are written by ``seon.turn/act!`` and left out of the pull; the debug page reads them (```seon.render.ui/debug-input```).
- **Messages** name their recipients (`:seon.message/to`); an embedding is a `:seon.vector/of` entity.
- **Processes** (`:seon.process/*`, one a spawn, keyed by agent key and pid): pid, form, fork and end times, a stall, written by ``seon.task/agent-tx``. The current one is ``seon.task/newest-process``; ``seon.problems/stalled-agents`` reads the stall.
- The agent itself keeps its key, id, root, branch, task, status and description.

## Provenance: who made each fact

Who made a fact is the tx-meta of its transaction. Every transaction names its branch (`:seon.tx/branch`, ``seon.db/tx-meta``); an agent's write during an eval also names that eval (`:seon.tx/eval`: ``seon.repl/eval-string`` holds the running eval's id and ``seon.agent/transact!`` writes it); a merge of main names the branch it merged (`:seon.tx/merged`).

``seon.ctx/made-by`` answers it for one attribute of one entity in two seeks (`(seon.ctx/made-by (my.db/db) (:db/id @ctx) :lab.ctx/steps)`). The ctx block shows it beside each attribute: the eval that wrote it, "from :main", or "merged from :main" (`seon.ctx-test/a-change-names-its-eval`). Root's own writes name no eval, and a join's members show none.
