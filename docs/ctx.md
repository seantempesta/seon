# ctx: the agent's record as one map

An agent reads its own record with `@ctx`: one map of its agent entity, its runs with their task, turns and messages, and its evals, with `:t`, `:branch` and `:commit` beside them (``seon.ctx/ctx``). It is a plain Clojure atom, kept current as the agent works, so a read is a deref and nothing is built for it. The design, the measurements and the owner's rulings behind it are in `notes/design-ctx-atom-2026-10-25.md`; the task is `seon.ctx/the-record-is-an-atom`.

## The map

The map is the agent's entity whole (every attribute it holds, `:seon.agent/key` and whatever the agent writes on it) and the units its entity schemas declare (``:seon.render/units``, as the [page's walk](render.md) reads them), nested:
- a reverse-ref unit is a map of its members by their identity, under the plural of the thing that holds the ref: `:evals` (``:seon.eval/_agent``, by `:seon.eval/id`), `:runs`, and in each run `:turns` and `:messages`;
- a forward-ref unit is its entity under the attribute's name: a run's `:task`;
- every other attribute keeps its qualified name, a cardinality-many one as a set, a [plain value](storage.md#plain-values) as itself (a vector keeps its order).

Things are named by themselves: an eval by its id, a run by its id, an attribute by its keyword.

## Kept by the change, never rebuilt

``seon.ctx/build`` reads the record once, when the agent's state first holds its db (a spawn, a resume, a clone), and ``seon.ctx/patch`` keeps it from each transaction's datoms, which ``seon.agent/transact!`` records with the db they made: a datom of an entity the map holds sets or removes its attribute, and a new entity whose ref names a held one by a unit's attribute joins it, read whole. One entity can sit at several paths (each run of a task holds the task), and each is patched. A move with no transaction (a merge of main, a resume) is patched from the datoms the two values differ by. A patch costs its change: six contracted calls for a new eval at 100 and at 1,000 evals (`seon.ctx-test/the-patch-costs-the-change-not-the-session`), and after each of 150 random changes of a session's kinds the patched map equals a fresh build (`seon.ctx-test/ctx-is-the-record-after-every-transaction`). The atom lives as long as the agent's process, across the runs of its session.

## What the model reads, and what the page draws

`@ctx`'s value carries `{:seon.render/schema :seon.ctx/ctx}` as metadata. ``:seon.ctx/ctx`` declares a page render, ``seon.render.ui/ctx``, and none for the model, so the model reads `@ctx` as it reads any value, through the one printer, cut where it is large with the form that reads the rest. The page draws the same eval as a block: a row per key, each value by the render its key's own schema declares (``seon.render/render`` with the key as its schema), else the printer's structure.

The block is drawn in the agent, where its renders are defined, when the eval is recorded (``seon.repl/record-details``), and kept as its HTML (``seon.web.html/raw``): root's page shows it as drawn and calls nothing of the agent's. The record keeps the text the model read and the block, not a copy of the map. The renders a branch registered are the ones it draws by: the REPL binds malli's registry to its branch's population while it evaluates and records, and ``seon.contract/properties`` reads a bound registry first.

## Adding a datum and its render

An agent adds to its record, and to what it sees of it, with four evals (`seon.ctx-test/an-agent-adds-a-datum-and-its-render`):

```clojure
(my.schema/register! :lab.ctx/steps '[:vector {:seon.render/html lab.ui/steps} :string])
(ns lab.ui) (defn steps "The plan's steps, numbered." {:malli/schema [:=> [:cat [:vector :string] :map] :any]} [v _] [:ol (for [s v] [:li s])]) (in-ns 'agent.a1)
(my.db/transact! [{:seon.agent/key (:seon.agent/key @ctx) :lab.ctx/steps ["read the plan" "write the test"]}])
@ctx
```

A registered key is an attribute at once when no entity map lists it (``my.schema/registration`` carries its declaration). The next `@ctx` holds it, and its block draws it by `lab.ui/steps`, on the agent's branch, unmerged. A class a render names is styled only if the page's stylesheet was built with it.

## Not yet

`swap!` changes the map only; writing through it (a validator turning a change into one checked transaction), provenance, the diff from main and its merge mark are the plan's next steps.
