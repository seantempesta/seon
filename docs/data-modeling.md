# Data modeling

Seon's data is Datahike entities: a thing is an entity with any number of attributes and values, things relate by refs, and every read is a seek in an index, so you design data by asking what will read it. Read this before adding an attribute, an entity or a query.

## A thing is its attributes

An entity is an id and whatever attributes it holds, and that is what makes it what it is. Two entities of one kind need not hold the same attributes, and an attribute costs nothing on an entity that does not have it, so never fill one with a placeholder: its absence is the answer. An agent has turns, evals, messages and a task, because those facts are about it; it is just data.

Say what a value is by its attribute's schema in `resources/seon/schemas/`, or at your REPL with ```my.schema/register!```:

| You want | Declare | Example |
|---|---|---|
| A fact | its type | `:seon.eval/code :string` |
| A name that finds the entity | `{:seon.db/identity true}` | `:seon.agent/key` |
| A name derived from content, the same on every branch | `{:seon.db/identity true :seon.db/content-id true}` | `:form/id` |
| Several values | `[:set ...]` | `:form/refs` |
| A link to another entity | `:seon.db/ref` | `:seon.eval/agent` |
| A value you will look things up by | `{:seon.db/index true}` | `:seon.eval/turn` |
| A value that is a whole map or vector | `:seon.db/plain` ([plain data](storage.md#plain-data)) | `:seon.eval/response` |

## Things relate by refs

A relationship is one ref, written on the many side, and read from either end. An eval names its agent (`:seon.eval/agent`), and the agent's evals are that ref read in reverse, `{[:seon.eval/_agent :as :evals] [*]}` in a pull, so nothing on the agent needs updating when an eval is added. A many-to-many relationship is a set of refs on one side.

Reach for a new entity only when a thing has facts of its own that something reads, and has a life of its own. A number or a keyword on the records it describes is often all there is: a turn is `:seon.eval/turn` and `:seon.message/turn` on each record, and no entity sits between an agent and its evals ([turn](turn.md#the-turn-is-a-number)). Never group things under an entity only so they can be listed together: a value they share lists them.

What happened before needs no entity either. A task's earlier states are read from the db's [history](storage.md#history), and an agent's work from its branch.

## A ref or a value

Before declaring a ref, ask what the fact means to whoever writes it: a statement about an entity that must exist, or an observation of a name. A statement is a ref: an eval belongs to an agent. An observation is a value: a form that mentions `seon.task/run` saw a name, and whether anything by that name exists is a separate question, so `:form/refs` holds symbols. A value survives what it names being deleted or renamed, and "names something with no entity" is one query. The tell for a name stored as a ref is machinery to survive a rename.

## Absence and retraction

An attribute with no value has no datom, so a stored nil and an empty set do not exist: clear a value by retracting it, and omit what is absent. This has a consequence: "found nothing" and "never looked" are the same bytes. If a reader must tell them apart, the looking is a fact of its own, a positive one naming what it read.

`[:db/retractEntity e]` retracts more than the entity. It also retracts every ref pointing at e, on the entities that hold them, and recursively what e owns through a component attribute. `[:db/retract e a]` and `[:db/retract e a v]` touch only those datoms. So to remove something from a list and keep it, retract the ref: a `swap!` on `@ctx` unlinks ([ctx](ctx.md#changing-it-swap)). A ref to a retracted entity is legal and reads as `{:db/id n}`, a link to nothing, and a join simply drops it, so check for that rather than trust it.

## A read is a seek in an index

Datahike keeps datoms sorted three ways, and each question has one read. Measured on 4,273 vars, allocation of one read:

| Question | Read | Index | Allocates |
|---|---|---|---|
| Which entity has this name | ```seon.db/eid``` or `(d/datoms db :avet :var/name n)` | AVET | 9.7 KB |
| These attributes of this entity | `(d/pull db [:var/name :var/doc] e)` | EAVT | 32 KB |
| Everything with this attribute | `(d/datoms db :aevt :var/doc)` | AEVT | the attribute's datoms |
| The same as the first, by query | ``(d/q '[:find ?e :in $ ?n :where [?e :var/name ?n]] db n)`` | a plan, then AVET | 89 KB |
| A value of an attribute with no index, by query | ``(d/q '[:find ?e :where [?e :var/doc "x"]] db)`` | every datom of the attribute | 2,775 KB |

Use a query when you are joining facts across entities. When one attribute or one entity answers the question, use `d/datoms` or `d/pull`, and name the attributes you need instead of `*`: [small answers](agent.md#small-answers) has the forms.

## What you look up by value must be indexed

AVET holds an attribute only if it is an identity, a ref or declared `{:seon.db/index true}`, and **a seek on any other attribute answers empty with no error**, which looks exactly like no data. So when you write `(d/datoms db :avet attr v)`, check the attribute's declaration first. `(d/schema db)` says: `:db/unique` or `:db/index`, or `:db/valueType :db.type/ref`. Adding the index later is safe, since an index added to a kept store reads the datoms it already holds (`seon.db-test/opening-a-kept-store-installs-the-attributes-it-lacks`).

## What you read is what you depend on

A render runs again when a datom touches what it read, so a narrow read costs less now and is drawn less often later ([what a read scanned](storage.md#what-a-read-scanned)). A seek by value is keyed by exactly that attribute and value, and only a datom with that value touches it. A pull is keyed by that entity's attributes. A scan of an attribute is touched by every write to that attribute anywhere.

The same holds for `@ctx`: read the path you need, since walking `(:evals c)` to find a few depends on every eval ([reactive renders](render.md#reactive-renders)). So look up the records of turn 7 by their number, and do not filter every eval to find them.

## Work is progressive

Everything here builds on what is already there, so a change costs what changed, and a cost that grows with the size of the session is a defect in the design, never a thing to optimize later. A transaction says which datoms moved, so one member joining a collection costs that member (```seon.ctx/window-moves```), and a render draws only what those datoms touch. If you are about to re-read, re-sort or re-print something whole because one piece of it changed, stop: there is a shape where you do not need to. A component reads what it draws and nothing around it; a function that rebuilds a whole list inside each of its members is quadratic however fast it looks at small sizes.

So derive nothing you can read, and store nothing you can derive: a value computed from the db at t is drawn by a render and kept by the walk's cache, which anything may evict ([AGENTS §5](../AGENTS.md#5-laws)). Never store a printed value: a value is stored as data, and only the [one printer](render.md#one-printer) makes text of it, for context.

## Before you add something

1. **What question does it answer, and what reads it?** If nothing reads it, it is not stored. If the answer is already an attribute or a query away, use that.
2. **Whose fact is it?** Put it on the entity it is about, and on the many side of a relationship.
3. **How will it be found?** By name: an identity. By a shared value: `{:seon.db/index true}`. By its entity: nothing more.
4. **Does a change cost what changed?** Write down what a new member costs, and prove it with a [cost proof](cost.md) if its input grows.

The shape is ours to change: rename an attribute, add a ref, reverse its direction. The standing store is test data, so a change of shape needs no migration ([machine](machine.md#disk-stays-bounded)).
