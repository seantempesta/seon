# Data modeling

Seon's data is Datahike entities: a thing is an entity with any number of attributes, things relate by refs, and every read is a seek in an index, so you design data by asking what will read it. Read this before adding an attribute, an entity or a query.

## A thing is its attributes

An entity is an id and whatever attributes it holds. Two entities of one kind need not hold the same attributes, and an absent attribute costs nothing, so never fill one with a placeholder: its absence is the answer.

Declare a value by its attribute's schema in `resources/seon/schemas/`, or at your REPL with ```my.schema/register!```:

| You want | Declare | Example |
|---|---|---|
| A fact | its type | `:seon.eval/code :string` |
| A name that finds the entity | `{:seon.db/identity true}` | `:seon.agent/key` |
| A name derived from content, the same on every branch | `{:seon.db/identity true :seon.db/content-id true}` | `:form/id` |
| Several values | `[:set ...]` | `:form/refs` |
| A link to another entity | `:seon.db/ref` | `:seon.eval/agent` |
| A value you will look things up by | `{:seon.db/index true}` | `:seon.message/from` |
| A value that is a whole map or vector | `:seon.db/plain` ([plain data](storage.md#plain-data)) | `:seon.eval/response` |

## A schema is a set of keys written together

An entity schema says which keys go together: an entity holding any key it requires is that thing and must hold them all, and an entity is as many things as the schemas its keys match ([decisions](decisions.md)). The write holds every entity it touches to them ([the write checks every entity it touches](storage.md#the-write-checks-every-entity-it-touches)).
- **Open maps.** A key a schema does not list is another thing's business, so adding an attribute never breaks an entity.
- **Required only for keys always written together.** A key a writer ever writes alone is optional. Making a key required is the one change that can break stored data, and the write shows which.
- **A membership ref is never required.** Which entity another belongs to (an eval's agent, a message's recipient) is a link retracted and made again while the entity stays.
- **What exists before its file is not required to have one.** A schema registered at the REPL lives in the db before any write-back.
- **One definition.** A function's contract cites the attribute's schema (`:seon.agent/branch`), never restates its type. A set of keys exists as a map schema only where they must go together.
- **What an entity is is read from an index the reader takes.** ```seon.db/entity-index``` derives it once from a set of schema forms, and every reader (```seon.db/most-specific```, ```seon.db/parents```, ```seon.db/extends?```, ```seon.db/attributes-of```, ```seon.db/implied-pattern```, ```seon.db/reaching```, the renders and ctx) takes it as `ix`. The binary's is ```seon.db/index```; a branch's, built from the schema entries its db holds, is ```seon.db/index-of``` (```seon.program/index-of-db``` for a db), so a schema an agent registered is known to its own index only. Its holder builds it again when a schema entry moves; building one costs the registry's size (about 1 ms), so no cache keyed by content would save anything.
- **Validate what is there.** A value is held to its attribute's schema at the write ([every datom](storage.md#the-write-checks-every-datom)); an entity schema adds only which keys come together.

## Things relate by refs

A relationship is one ref, written on the many side and read from either end. An eval names its agent (`:seon.eval/agent`); the agent's evals are that ref read in reverse, ``{:seon.eval/_agent [...]}`` in a pull, so nothing on the agent changes when an eval is added. Seon's patterns never rename a ref with `:as`, so a path into `@ctx` names an attribute. A many-to-many relationship is a set of refs on one side.

**A ref names what it points at, once, on its own schema:** `:seon.eval/agent [:and {:seon.db/to [:seon.db/agent-fact]} :seon.db/ref]`, or on a set's `:set`, with several targets when it may point at several things. From that one fact every reader knows the links both ways: the entity index's `:targets`, ```seon.db/reaching``` (the refs that point at a schema) and ```seon.db/implied-pattern``` (the pull a schema implies, each schema walked once, so no join loops). No schema restates another's links (`seon.ctx-test/the-hand-written-joins-are-implied-by-the-refs`, `seon.ctx-test/an-agent-pulled-by-its-implied-joins-validates`). A target is a link, never what the ref's value is checked against, so a test's reach does not follow it (```seon.program/less-targets```).

Make a new entity only for a thing with facts of its own that something reads and a life of its own; a number or keyword on the entities it describes is often enough. Never group things under an entity only to list them: a value they share lists them. What happened before needs no entity: a task's earlier states are in the db's [history](storage.md#history), an agent's work on its branch.

## A ref or a value

A statement about an entity that must exist is a ref (an eval belongs to an agent); an observation of a name is a value (`:form/refs` holds the symbols a form mentions). A value survives what it names being deleted or renamed. The tell for a name stored as a ref is machinery to survive a rename.

When something happened is its transaction's time: a datom's `:tx` and that transaction's ``:db/txInstant`` (a pull asks it with `[attr :tx true]`, [ctx](ctx.md#the-pattern)). Order by `[:db/txInstant, tx id]`, never by an id alone: a merge carries a branch's transactions with their own ids, so id order on a branch is not write order ([history](storage.md#history)).

## Absence and retraction

An attribute with no value has no datom: there is no stored nil or empty set, so clear a value by retracting it. "Found nothing" and "never looked" are then the same bytes; a reader that must tell them apart needs the looking as a fact of its own.

`[:db/retractEntity e]` also retracts every ref pointing at e and what e owns through a component attribute; `[:db/retract e a]` touches only those datoms. To remove something from a list and keep it, retract the ref (a `swap!` on `@ctx` unlinks, [ctx](ctx.md#changing-it-swap)). A ref to a retracted entity reads as `{:db/id n}` and a join drops it.

## A read is a seek in an index

Datahike keeps datoms sorted three ways, and each question has one read (allocation measured on the standing root at about 4,000 vars):

| Question | Read | Index | Allocates |
|---|---|---|---|
| Which entity has this name | ```seon.db/eid``` or `(d/datoms db :avet :var/name n)` | AVET | 9.7 KB |
| These attributes of this entity | `(d/pull db [:var/name :var/doc] e)` | EAVT | 32 KB |
| Everything with this attribute | `(d/datoms db :aevt :var/doc)` | AEVT | the attribute's datoms |
| The first, by query | ``(d/q '[:find ?e :in $ ?n :where [?e :var/name ?n]] db n)`` | a plan, then AVET | 89 KB |
| A value of an unindexed attribute, by query | ``(d/q '[:find ?e :where [?e :var/doc "x"]] db)`` | every datom of the attribute | 2,775 KB |

Use a query to join facts across entities; when one attribute or one entity answers, use `d/datoms` or `d/pull` and name the attributes instead of `*` ([small answers](agent.md#small-answers)).

## What you look up by value must be indexed

AVET holds an attribute only if it is an identity, a ref or declared `{:seon.db/index true}`, and a seek on any other attribute answers empty with no error. Check `(d/schema db)` for `:db/unique`, `:db/index` or `:db.type/ref` before a `(d/datoms db :avet attr v)`. An index added later reads the datoms a kept store already holds (`seon.db-test/opening-a-kept-store-installs-the-attributes-it-lacks`).

## What you read is what you depend on

A render runs again when a datom touches what it read, so a narrow read costs less now and later ([what a read scanned](storage.md#what-a-read-scanned)). A seek by value is touched only by that value; a pull by that entity's attributes; a scan of an attribute by every write to it. In `@ctx` read the path you need: walking ``(:seon.eval/_agent c)`` to find a few depends on every eval ([reactive renders](render.md#reactive-renders)).

## Work is progressive

A change costs what changed: a cost that grows with the size of the session is a defect in the design. A transaction says which datoms moved, so one member joining a collection costs that member (```seon.ctx/window-moves```), and a render draws only what those datoms touch. If you are about to re-read, re-sort or re-print something whole because one piece changed, find the shape where you need not.

Derive nothing you can read, and store nothing you can derive ([derived data](#derived-data-is-a-cache-never-relied-on)). Never store a printed value: only the [one printer](render.md#one-printer) makes text of a value, for context.

## Derived data is a cache, never relied on

A value derived from the db (a score, a selection, a render, a diff, a test's verdict) may be cached, keyed by its inputs (a digest, a db's t) and never by a clock; anything may evict it, and its reader regenerates it from the db as of t (owner, 2026-10-28: "Derived data may be cached with the understanding that it may be evicted at any time so the process must always have a way to generate the data from database time T."). A derived value that cannot be regenerated is a fact that belongs in the db, or a defect. Results are reused by the digest of their inputs the same way ([merging](merging.md#verdicts-are-a-cache)).

## Where each entity's facts live, and which pull reaches them

A pull reads the one db it is made on, so where a fact is written decides who can pull it. Before a fix that touches an attribute, a ref or a pattern clause, write the pull you want and find here which db holds what it asks.

| entity | identity | on the agent's own branch | on main | reached by |
|---|---|---|---|---|
| an agent | `:seon.agent/key` | its start: key, task, `max-turns` for a spawned agent; its turns: `turn`, `ns`, `test-error`, `main-commit`, `main-program`, `base-commit`; its `swap!`: `:seon.ctx/pattern`, `:seon.render/hiccup`, keys it registers; its end: `outcome`, `reason`, `ended-turn` | the system's observations: `status`, `branch`, `boot`, `started-ms`, `:seon.agent/parent`, the evals a person opened | its own pull (the top of `@ctx`); on main, `:seon.agent/_parent` of whoever started it |
| a task | `:seon.task/id` | what its agent edits: `plan`, `instructions`, `proofs`, carried to main by the merge | task files and the reconciler: execution, `outcome`, `reason`, `reasons`, commit, error | `{:seon.agent/task ...}`; `:seon.task/needs`; a cite |
| a model call | `:seon.model-call/id` | the turn that made it | nothing until the merge | `{[:seon.model-call/_agent :tx :at] ...}` |
| an eval | `:seon.eval/id` (```seon.repl/eval-id```, from its call and place in the reply) | the REPL that ran it | nothing until the merge | `{[:seon.eval/_agent :tx :at] ...}`, a call's `:seon.eval/_call` |
| a message | `:seon.message/id` | its delivery, a transaction of the recipient's branch | where it was sent | `{[:seon.message/_to :tx :at] ...}`; `:at` is when it reached this branch |
| a cite | `:seon.cite/id` | whoever cited (`my.agent/cite!`), or a plan naming an address | a task file's plan | `{:seon.cite/_of ...}` of the agent and its task |
| a process | `:seon.process/id` | never | the system: pid, fork and end, a stall | on main, `:seon.process/_agent` |
| the system | `:seon.system/id` | never | the boot and each landing | root's page |

What follows:
- **A fact main keeps about an agent is not in that agent's own pull** (`status`, `branch`; an agent attached with `seon_attach` also lacks `max-turns`). A render that needs one reads it where it lives, never through a second hand-written pull.
- **Nothing an agent did is on main until its branch merges.**
- **A computed value (a status, a spend, a conflict) is in no db:** a render computes it from what the pull holds.
- **A process's own state is in no db**: the test service's queue, an effect's counters.

`(doc :the/attribute)` prints an attribute's schema form; `(get (d/schema (my.db/db)) :the/attribute)` answers its `:db/cardinality` and `:db/valueType`; ```seon.contract/properties``` holds what a schema declares beside its shape (a render, a description). For one entity, `(my.db/pulled @ctx)` lists every clause with what this db holds for it ([looking at what you pulled](agent.md#looking-at-what-you-pulled)).

## Before you add something

Four questions settle most new data, and each can end with adding nothing.

1. **What reads it?** If nothing does, it is not stored; if it is already an attribute or a query away, use that.
2. **Whose fact is it?** Put it on the entity it is about, on the many side of a relationship.
3. **How will it be found?** By name: an identity. By a shared value: `{:seon.db/index true}`. By its entity: nothing more.
4. **Does a change cost what changed?** Write down what a new member costs, and prove it with a [cost proof](cost.md) if its input grows.

The shape is ours to change: the standing store is test data, so a rename needs no migration ([machine](machine.md#disk-stays-bounded)).
