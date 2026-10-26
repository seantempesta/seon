# ctx: the agent's record as one map

An agent reads its own record with `@ctx`: one map of its agent entity, its runs with their task, turns and messages, and its evals, with `:t`, `:branch` and `:commit` beside them (``seon.ctx/ctx``). It is a plain Clojure atom, kept current as the agent works, so a read is a deref and nothing is built for it. The design, the measurements and the owner's rulings behind it are in `notes/design-ctx-atom-2026-10-25.md`; the task is `seon.ctx/the-record-is-an-atom`.

## The map

The map is the agent's entity whole (every attribute it holds, `:seon.agent/key` and whatever the agent writes on it) and the units its entity schemas declare (``:seon.render/units``, as the [page's walk](render.md) reads them), nested:
- a reverse-ref unit is a map of its members by their identity, under the plural of the thing that holds the ref: `:evals` (``:seon.eval/_agent``, by `:seon.eval/id`), `:runs`, and in each run `:turns` and `:messages`;
- a forward-ref unit is its entity under the attribute's name: a run's `:task`;
- every other attribute keeps its qualified name, a cardinality-many one as a set, a [plain value](storage.md#plain-values) as itself (a vector keeps its order).

Things are named by themselves: an eval by its id, a run by its id, an attribute by its keyword.

## Kept by the change, never rebuilt

``seon.ctx/build`` reads the record once, when the agent's state first holds its db (a spawn, a resume, a clone), and ``seon.ctx/patch`` keeps it from each transaction's datoms, which ``seon.agent/transact!`` records with the db they made: a datom of an entity the map holds sets or removes its attribute, and a new entity whose ref names a held one by a unit's attribute joins it, read whole. One entity can sit at several paths (each run of a task holds the task), and each is patched. A move with no transaction (a merge of main, a resume) is patched from the datoms the two values differ by. A patch costs its change: six contracted calls for a new eval at 100, 1,000 and 3,000 evals (`seon.ctx-test/the-patch-costs-the-change-not-the-session`). After each of 150 random changes of a session's kinds, the patched map equals a fresh build (`seon.ctx-test/ctx-is-the-record-after-every-transaction`). The atom lives as long as the agent's process, across the runs of its session.

**What changed, and what did not.** A patch answers `:changed`, the paths its transaction changed: an attribute's, or a member's that joined or left. It changes only along those paths. So every value at a path not at, under or above one of them is the same object it was (`identical?`), and a part's memo is a pointer test. The same property test holds this after each of the 150 changes. The environment (`:t`, `:branch`, `:commit`) is the root map's, new each transaction and no path of `:changed`, so nothing caches on the root map. A build answers `:changed #{[]}`, the root path: every value is new, and a memo misses once, everywhere. The test counts the paths it checked (over 1,000 in 150 changes), so a patch that reported everything changed could not pass it.

**What changed over a turn is data.** ``seon.ctx/diff`` answers what changed of the record from a turn's start to its end, in the record's own shape: `{path [old new]}`, old nil for a member that joined, new nil for one that went. It is pure in the record at the two t's. ``seon.ctx/advance`` patches the start's record by the datoms the start and end db values differ by (the net change of the turn's transactions, read from the db alone), so its `:changed` bounds where they are compared. A path equal at both ends is dropped (a change undone within the turn), and so is one under another the diff holds. The start's record with the diff applied is the end's, and the advanced record equals a build at the end t (`seon.ctx-test/a-turns-diff-is-what-changed-between-its-ts`, twelve turns of random changes). `diff` costs its changed paths, never the record: one contracted call for an eval added, at 100, 1,000 and 3,000 evals (`a-turns-diff-costs-its-changed-paths-not-the-record`); `advance` costs the patch of the net datoms. A patch applies every entity's retractions before any assertion, so a member retracted and made again under its key within a turn comes out as the end's.

**ctx is the db's data, nothing derived.** (owner, 2026-10-26: "no we don't store derived data in the db or the ctx atom".) What a view needs beyond the pull is a render over the db, memoized by what it read, never a member of ctx. The conversation's order is each entry's first transaction, ``seon.context/first-t`` (one seek; it never changes, so a view memoizes it by entity).

**Any entity, any branch.** ``seon.ctx/build`` takes an entity id: an agent's (its `@ctx`), root's, the user's, a task's, a run's. ``seon.ctx/follow`` patches a record from one of the writer's commits as main's readers receive them (`{:t :ops :db}`), as an agent's follows its own transactions. A run or task retracted leaves its environment alone, as a build of no entity does (`seon.ctx-test/any-entitys-record-follows-mains-commits`, 60 commits, two records).

**The task and related are renders.** A render is a function and the data it renders. A task is rendered by ``seon.context/render-task`` over the task and the db as of t, with ``seon.context/render-task-key`` its memo key: the accumulated plan, instructions and proofs, and the newest transaction of each entity it reads (each cited var and the forms that name it, each proof's var, each cited section). Equal keys, an equal render (`seon.context-test/a-task-renders-key-moves-with-what-it-reads`; 14 contracted calls, flat in the wiki, `seon.context-cost-test/a-task-renders-key-costs-what-the-plan-names`). The known gap until the task's references are refs in ctx: render-task parses the plan's text and reads what it cites from the db, so its key stands beside ctx's identity. A var the db holds is read from its facts (``seon.docs/resolve-ref``: its doc, contract, recorded source and the arglists it declares); a library var the db does not hold, from the process's meta, which the build fixes; a cited file's text on disk is not read from the db. A view draws the task's part from its render of now, on every view, so the page is never stale (`seon.context-test/a-cited-var-redefined-shows-in-the-next-view`): 18 contracted calls a view, flat in the wiki (`seon.context-cost-test/a-task-render-costs-what-the-plan-names`). Related is ``seon.context/related`` over the plan's vectors, with ``seon.context/related-key`` its memo key (16 calls: render-task-key, the count, and the newest vector by one seek of the indexed `:seon.vector/text-t`). A view reads the db only through these (``seon.context/renders``). The request's prefix is the task rendered as of the session's first turn (`seon.context-test/the-prefix-stays-byte-identical-across-turns`).

The other facts a view reads:
- A catch-up (``seon.agent/merge-main!``) is a `:seon.catch-up/*` entity written in the merge's own transaction: the branch and t merged, what applied and what conflicted. It is the agent's unit `:catch-ups` (`seon.agent-test/after-merge-main-the-agent-judges-against-the-new-base`).
- Proof results and the done rule's reasons are each turn's `:seon.turn/basis` `:observed`, already in ctx under `[:runs id :turns tid]`. A message the turn takes is written on the branch (``seon.turn``'s `take!`) and sits under `[:runs id :messages]`.

## What the model reads, and what the page draws

`@ctx`'s value carries `{:seon.render/schema :seon.ctx/ctx}` as metadata. ``:seon.ctx/ctx`` declares a page render, ``seon.render.ui/ctx``, and none for the model, so the model reads `@ctx` as it reads any value, through the one printer, cut where it is large with the form that reads the rest. The page draws the same eval as a block: a row per key, each value by the render its key's own schema declares (``seon.render/render`` with the key as its schema), else the printer's structure.

An eval of `@ctx` is recorded as its basis, never as a copy: the value's metadata names it (`:seon.render/basis`, the branch and t it is the record of), and ``seon.repl/record-details`` keeps `{:schema :basis}` and the text the model read (a session of 3,000 evals has a 3.9 MB record, which a copy would store again at every `@ctx`). The page renders what exists: ``seon.render.ui/evaluation`` rebuilds the map from the agent's branch as of t (``seon.ctx/build``) and draws it.

A key whose schema the branch registered or changed is drawn by the agent, since its render may be defined only there and root cannot call it. ``seon.render.ui/ctx`` shows that key's structure, naming its render, and notes what it wants drawn; the render proc asks the agent for it while the page is viewed (``seon.render.proc/comp-entry``, an `{:op :render}` request through root's api to the agent, answered by ``seon.repl/agent-step`` under the branch's schemas, recording nothing), holds the answer, and renders the component again with it. An agent that is gone answers nothing, and its key keeps its structure, saying so, until the agent runs again or its render merges to main and root calls it. Nothing of the block is stored, so the model never reads HTML.

## Adding a datum and its render

An agent adds to its record, and to what it sees of it, with four evals (`seon.ctx-test/an-agent-adds-a-datum-and-its-render`):

```clojure
(my.schema/register! :lab.ctx/steps '[:vector {:seon.render/html lab.ui/steps} :string])
(ns lab.ui) (defn steps "The plan's steps, numbered." {:malli/schema [:=> [:cat [:vector :string] :map] :any]} [v _] [:ol (for [s v] [:li s])]) (in-ns 'agent.a1)
(my.db/transact! [{:seon.agent/key (:seon.agent/key @ctx) :lab.ctx/steps ["read the plan" "write the test"]}])
@ctx
```

A registered key is an attribute at once when no entity map lists it (``my.schema/registration`` carries its declaration). The next `@ctx` holds it, and its block draws it by `lab.ui/steps`, drawn by the agent, unmerged. A class a render names is styled only if the page's stylesheet was built with it.

## Changing it: swap!

An agent changes its record as it changes any atom: `(swap! ctx assoc :lab.ctx/steps [...])`, `(swap! ctx update :evals dissoc "e3")`, `(swap! ctx assoc-in [:runs id :seon.run/status] :done)`. The atom's validator (``seon.ctx/write!``) turns the change into one transaction on the branch: each attribute set or retracted (a cardinality-many one member by member), each unit's member new (with its own units, naming its parent by the unit's ref and keyed by its identity), gone (retracted whole) or changed. Every value is checked by its schema, the platform's or one the branch registered; a dry run (`d/with`) checks the db would hold what the swap! says at each path it writes; then it is applied (``seon.agent/transact!``), and when the swap! ends the record is the db's (its new entities with their ids). Finding the change costs the change: a unit identical to its old self is skipped by one `identical?` (`seon.ctx-test/a-write-costs-its-change-not-the-record`: one attribute of the agent's entity, 5 contracted calls and 2,709 ticks at 100 and at 1,000 evals); a unit that differs is walked member by member, each identical member skipped the same way. The write itself adds one `d/with` and the patch of its datoms, which costs the change (the patch's own proof); `write!` as a whole is not measured.

Refused, nothing written, with the error that says why (`seon.ctx-test/a-swap-writes-the-record`):
- ``:seon.ctx/invalid``: a key with no schema (register it, a qualified keyword), or a value its schema refuses, each with its path and why;
- ``:seon.ctx/not-held``: the db would not hold what the swap! says;
- ``:seon.ctx/basis``: the map's `:t`, `:branch` or `:commit` is not the record's: the swap!'s function wrote the db (it is refused once, never retried, which closes the livelock a retrying swap! would be), or wrote the environment; or it is no map (a `reset!`).

The record's environment is read-only; everything else is writable, and history keeps what a write retracts.

**The screen is the agent's to change.** `(swap! ctx assoc :seon.agent/screen ["(my.task/status)" "(:lab.ctx/steps @ctx)"])` sets the forms its next screen evaluates (``seon.turn``'s screen reads the record; absent, ``seon.screen/forms``).

## Provenance: who made each fact

Who made a fact is a query on the tx-meta of its transaction, Datomic's reified transactions. Every transaction Seon makes names its branch (`:seon.tx/branch`, ``seon.db/tx-meta``). An agent's write during an eval also names that eval (`:seon.tx/eval`, its `:seon.eval/id`, "e5"): ``seon.repl/eval-string`` holds the running eval's id on the agent's state while its forms run, and ``seon.agent/transact!`` writes it, so a `swap!`, any `transact!` its code makes, and the eval's own record all name it. A catch-up (``seon.agent/merge-main!``) also names the branch it merged (`:seon.tx/merged`), so a fact main made reads as main's after the agent caught up, though the agent's branch wrote the merge.

``seon.ctx/made-by`` answers it for one attribute of one entity: the tx-meta of its newest datom's transaction, by two seeks (`(seon.ctx/made-by (my.db/db) (:db/id @ctx) :lab.ctx/steps)`). The page's ctx block shows it beside each attribute, as of the eval's t: the eval that wrote it, else "from :main" for a fact the agent was forked holding, and "merged from :main" for one a catch-up brought (`seon.ctx-test/a-change-names-its-eval`; the tx-meta itself, `seon.agent-test/an-agents-transactions-name-their-runtime` and `after-merge-main-the-agent-judges-against-the-new-base`).

Not covered: root's own writes (``seon.root``'s transactions on main) name no eval; a unit's members show no provenance on the block (each eval is its own record); a removed member is not shown as removed.

## Not yet

The diff from main and its merge mark are the plan's next step.
