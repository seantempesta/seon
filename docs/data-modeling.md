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
- **What an entity is is read from an index the reader takes.** ```seon.db/entity-index``` derives it once from a set of schema forms, and every reader (```seon.db/most-specific```, ```seon.db/parents```, ```seon.db/extends?```, ```seon.db/attributes-of```, ```seon.db/implied-pattern```, ```seon.db/reaching```, the renders and ctx) takes it as `ix`. The binary's is ```seon.db/index```; a branch's, built from the schema entries its db holds, is ```seon.db/index-of``` (```seon.program/index-of-db``` for a db), so a schema an agent registered is known to its own index only. Its holder builds it again when a schema entry moves; building one costs the registry's size (about 0.9M ticks and 2 ms, its ancestors and reaching refs made then so `extends?` and `reaching` are lookups), so no cache keyed by content would save anything.
- **Validate what is there.** A value is held to its attribute's schema at the write ([every datom](storage.md#the-write-checks-every-datom)); an entity schema adds only which keys come together.

## Things relate by refs

A relationship is one ref, written on the many side and read from either end. An eval names its agent (`:seon.eval/agent`); the agent's evals are that ref read in reverse, ``{:seon.eval/_agent [...]}`` in a pull, so nothing on the agent changes when an eval is added. Seon's patterns never rename a ref with `:as`, so a path into `@ctx` names an attribute. A many-to-many relationship is a set of refs on one side.

**A ref names what it points at, once, on its own schema:** `:seon.eval/agent [:and {:seon.db/to [:seon.db/agent-fact]} :seon.db/ref]`, or on a set's `:set`, with several targets when it may point at several things. From that one fact every reader knows the links both ways: the entity index's `:targets`, ```seon.db/reaching``` (the refs that point at a schema) and ```seon.db/implied-pattern``` (the pull a schema implies: each schema joined in full once, where it is nearest, and by its own attributes elsewhere, so no join loops and its size follows the schemas, never the paths to them). No schema restates another's links (`seon.ctx-test/the-hand-written-joins-are-implied-by-the-refs`, `seon.ctx-test/an-agent-pulled-by-its-implied-joins-validates`). A target is a link, never what the ref's value is checked against, so a test's reach does not follow it (```seon.program/less-targets```).

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

`(doc :the/attribute)` prints an attribute's schema form; `(get (d/schema (db)) :the/attribute)` answers its `:db/cardinality` and `:db/valueType`; ```seon.contract/properties``` holds what a schema declares beside its shape (a render, a description). For one entity, `(pulled @ctx)` lists every clause with what this db holds for it ([looking at what you pulled](agent.md#looking-at-what-you-pulled)).

## What an entity is: accreted attribute sets

This is the model being built (owner, 2026-11-17; tasks `seon.db/an-entity-accretes-its-attribute-sets`, `seon.render/an-entity-is-pulled-and-drawn-by-the-sets-it-matches`, `seon.turn/a-turn-writes-each-fact-once-from-its-proc`); until they land, the store holds the shapes in the rename table below. It is designed from the reads it serves: an agent's context and its page are one tree pulled from the store and drawn, so what is stored is laid out for that pull. Every number here was measured on 2026-11-17 (scratch Datahike values at the store's scale, 500 entities, mean of 100; or the live store, named by its handle).

### An entity is the sets it holds

An attribute set is the attributes of one concern, under one namespace, with core keys that say the set is there. An entity accretes sets as it becomes more, and what it is, is the sets it matches: there are no kinds (`AGENTS.md` §5) and no hierarchy. A branch is an entity with the branch set; a session drives it through the REPL, so it gains the agent set; a model runs its turns, so it gains the turn set; it works a task, so it holds the task set; its live process is the process set on it, written by root alone. A fresh attach changes its process set and makes no new entity; its past is Datahike's history.

| Set | Core keys | The rest |
|---|---|---|
| `:seon.branch` | `/name` (identity) | `/point` (the commit it was cut at, the past too), `/parent` (the branch it was cut from), `/upstream` (`:main` when it tracks main; absent for a private session) |
| `:seon.agent` | `/key` (identity), `/want` (indexed: `:running`, `:paused`, `:stopped`), `/driver` (`:session` or `:model`) | `/parent`, `/ns`, `/outcome` (a judgment, once), `/reason`, `/started-ms`, `/ended-ms`; its state is derived, never stored |
| `:seon.turn` | `/number` | `/ended`, `/waste`: present only when a model drives it |
| `:seon.task` | `/id` (identity), `/plan` | proofs, needs, kind, ... (today's) |
| `:seon.process` | `/key` (identity: `agent|<key>` on an agent; `fork-point|<build>|<commit>` on a fork point), `/pid` | `/holds` (the commit it runs), `/clean`, `/build` (its binary's tree digest; the path follows), `/boot`, `/fork-ms`, `/exit`, `/ended-ms`; root-only |
| `:seon.root` | `/boot` | `/stopped`, `/last-update`, `/collected`, `/quiescent`, `/previous-end`, `/signed-in`, `/source`, `/tasks`, `/content-t`, `/retained` |
| config | each key its schema names (`:seon.config.ai/model`, ...) | on the root, the global value; on an agent, its own override |

An entity's matches are a set of set names, each the set's schema compiled once as a malli validator: 1.5 µs for an entity against all of them (result/ba07092b). The root matches branch (`:main`), agent, root and config; a model's agent on a task matches branch, agent, turn and task; a fork point matches process alone.

**Split into another entity only when one of three forces it:** (1) many things share one value (main's config is the root's, read by every agent); (2) more than one exists at once: an agent's evals, model calls, messages, child agents, citations and searches are many an agent, so each is its own entity, joined by a ref to the agent (and several parallel attempts at one task would make each attempt its own entity; until they exist the task's set is on its agent); (3) a fact with no entity to live on (a fork point's process).

### The tree is pulled by the sets an entity matches

Today an entity is drawn by exactly one schema, the most specific its identity attributes name through an `[:and parent ...]` chain (```seon.render/entity-schema```), which throws `:seon.render/no-one-schema` when two match that extend neither, and patterns are copied down that chain (`:seon.db/agent-start-fact` repeats the agent's whole pattern). Accreted sets make one-schema-an-entity impossible, and they make it unnecessary.

**Store only what something is derived from; render the rest** (owner, 2026-11-17). Every stored attribute is a source fact some render or derivation reads; anything derivable is derived by a render, never stored. With stored data that lean, an entity's own attributes are pulled whole (`[*]` is right: nothing in them is unwanted), and every set has a render, so any entity, in any namespace or task, draws itself, and an agent always has good context of whatever it is working on. A join is different: it reaches another entity and what that entity joins, so a join names the sets it pulls there, with its window.

- **Each set declares its part of the tree and its part of the page:** its `:seon.ctx/pattern` (its own attributes, `[*]` when the set stores only source facts, and the joins it owns: the agent set joins its evals, model calls, messages and child agents; the task set its needs, proofs and examples; the turn set nothing more) and its `:seon.render/hiccup` (the component that draws that part), with its place on the page.
- **An entity's pattern is the union of its matched sets' patterns** (joins asked twice merged, as ```seon.render/expanded``` merges them now); **its page and its context are its matched sets' components**, each in its place, in the one frame. Nothing is chosen, so nothing is ambiguous, and an entity that gains a set gains its part of the page.
- **A join names the sets it pulls of the joined entity**, with its window: a child agent is pulled by the agent set's summary, an eval by the eval's set.

Measured today for comparison: building one agent's tree (```seon.ctx/build```) is ~3 ms for 4-8 evals and 1-12 model calls, 15-23 KB printed (result/a64a4bd3). The accreted entity removes the agent → task join and every process and system join from it; the joins left are the "many an agent" ones, each a reverse ref with its window.

### A turn writes each fact once

Today a turn's writes go through the agent's run-ahead value: ```seon.agent/transact!``` applies each write to an uncommitted db value held in the agent's state (`pdb/with`, agent.clj:501), readers read that value and patch the context from it, ```seon.agent/commit!``` writes it out at points of the turn, and a model call records a basis `t` "past its commit, which no later read can read back" (turn.clj:298). That breaks two rules: a kept db value is a reference, never a value, and a recorded `t` is one that exists. Instead:

- **The turn's state is its proc's** (a Flow proc's state, `docs/flow.md`): the request, the reply and the evals' answers accumulate there while the turn runs; what the person sees in flight is the stream (```seon.repl/stream-id```), which exists.
- **Each fact is written once, as one transaction, when it is true:** an eval's record with its answer when it lands; the model call with its reply when it ends; the turn's number with it. Each is a commit of the agent's branch through its one writer. No value ahead of the commit is held, read or patched from.
- **A basis is a commit that exists**: the branch's head the call read, as `{:branch :commit :t}`.

### Store what is wanted and what was observed; derive the rest

Only two kinds of fact about an agent are stored (owner, 2026-11-17: the minimal data, everything else derived and kept current by the reactions, `seon.ratom`): what is wanted, and what only an observer could know, each written once when it becomes true.

- **Wanted:** `:seon.agent/want` (`:running`, `:paused`, `:stopped`), and `:seon.agent/driver` (`:model`: the turn proc asks the model provider for each reply; `:session`: each reply comes from a session through MCP, as an outside agent's do now).
- **Observed:** the process set's `/pid` and `/boot` when root forks it, `/exit` when it ends; a turn's facts when they are true.
- **Derived, never stored:** its state. `:running` while it wants running, a model drives it, and its process is alive on this boot; `:driven` while a session drives it; `:starting` while it wants running and no live process holds it, after an exit or a restart, which is what brings it back from its branch, its checkpoint, with no recovery code; `:pausing` and `:stopping` until the process ends; `:paused`, `:stopped`. Waiting for a message is derived the same way (its last turn ended with no eval, and nothing is owed it). Measured on a scratch value: 20 µs an agent, and every case right, a process from the previous boot read as not alive (result/d693e83d).
- **The reconciler is the reaction** that closes the gap: a starting agent is forked onto its branch; a pausing one is told to stop at its next turn's boundary; a stopping one is ended. Nothing restarts an agent by hand.

DELETES: `:seon.agent/status` stored, `:seon.task/execution`, the parked and told outcomes stored on a task, the `"outside"` model that marks a session's agent today (`seon.reconcile/outside?`), and holding and releasing a task (`my.root/run!`), all replaced by `want` and `driver`.

### A session takes the wheel

A Claude session and a DeepSeek model drive an agent the same way: each writes the same turn facts, and only where the reply comes from differs. So a session takes over any agent by becoming its driver:

1. **Take the wheel:** the session sets the agent's `driver` to `:session` (it attaches to it); the model's turn loop stops at its next turn's boundary, and the agent's process, its branch's one writer, stays.
2. **Curate:** the session evaluates in that agent's REPL, so every edit goes through the agent's own writer: an eval that led it astray leaves its context by retracting its ref to the agent (the eval stays, in history and for review; measured, result/d693e83d), its task's plan or proofs are edited on the same entity, a message is sent, its own definitions are fixed.
3. **Hand back:** the session sets `driver` to `:model`; the next turn pulls its context from the curated store and goes on from there.

An agent curates its own history the same way, from its own REPL: the refine approach is that agent being its own driver for a turn. Pausing without curating is `want :paused` then `:running`.

### Every situation is a read

Each thing the system does starts from one read of this layout, timed on a scratch value at the store's scale.

| Situation | Read | Measured |
|---|---|---|
| Spawn an agent | one pull of its entity: branch, upstream, build, pid | 20.8 µs (from 44 through refs) |
| An agent's config (three keys) | the keys on the agent and on the root, the agent's win | 31-34 µs |
| What an entity is | its matched sets, compiled validators | 1.5 µs |
| A fork point | by its process key | 14.8 µs |
| Resume after a switch-over | agents by `/want` `:running`, each one's derived state from its `/pid`, `/boot` and `/exit` on the same entity | 95 µs for 20 (from 553); 20 µs an agent derived |
| Each turn: did main move | its `/holds` against main's head | one datom |
| A branch's tests | its clean fork point by key; a miss forks the build's and runs `seon.program/load-diff!` to the merged commit | ~15 µs, then the load |

(Scratch values: result/ce6eb25b, result/ba07092b.)

**A test needs a branch and a fork point, never an agent.** The harness takes the branch under test, finds its clean fork point by key, forks it, binds that branch as the context the agent API reads (`my.agent`, `my.db`), and runs the test in the child. A test that reads, or writes a db value with `d/with`, costs one fork (~65 ms) and creates nothing; one that needs a stored branch gets a new entity with the branch set, cut from the one under test and deleted after; only a test whose subject is an agent makes one; a process test runs on a candidate fork point. The "no branch" error that reddened 78 tests of the 2026-11-17 full run goes with the agent-state context.

### Rename table

Every attribute the store holds today (read 2026-11-17: the root's 13 `:seon.system` attributes, the agents' 21 `:seon.agent` attributes on 523 entities, the processes' 6 on 689), and where it goes. A stored keyword is renamed by erasing the store (`AGENTS.md` §7a): the model lands with a reseed.

| Today | Becomes |
|---|---|
| `:seon.system/*` on the root | `:seon.root/*`; the root also holds `:seon.agent/key` `"root"` and `:seon.branch/name` `:main` (the root is main's agent) |
| `:seon.system/config` (a ref to a config entity), `:seon.config/sources`, the per-key `:config/key` and `:config/form` entries | each config key as an attribute of the root |
| `:seon.agent/branch`, `/base-commit` | `:seon.branch/name`, `/point` |
| `:seon.agent/main-commit`, `/main-program` | `:seon.process/holds` |
| `:seon.agent/boot`; `/root`, `/system` | `:seon.process/boot`; the two refs go (one root) |
| `:seon.agent/model`, `/max-turns` | `:seon.config.ai/model`, `:seon.config.agent/max-turns` on the agent, set only to override |
| `:seon.agent/turn`, `/ended-turn`, `/waste` | `:seon.turn/number`, `/ended`, `/waste` |
| `:seon.agent/task` (a ref) | the task set on the same entity |
| `:seon.process/id`, `/agent` | `:seon.process/key`; the ref goes (the process set is on its agent) |
| `:seon.process/forked-ms` (a duration) | `:seon.process/fork-ms` |
| `:seon.task/env`, `/config`, `/build` | gone: the branch set, config keys, `:seon.process/build` |
| `:seon.db/agent-fact`, `agent-start-fact`, `user-fact`, `:seon.system/root-fact` as one-schema-an-entity | the sets above, each with its pattern part and component |
| `:seon.agent/status`, `:seon.task/execution`, a task's parked and told outcomes, the `"outside"` model | `:seon.agent/want` and `:seon.agent/driver`, stored; the state derived |
| `:seon.agent/key`, `/parent`, `/ns`, `/outcome`, `/reason`, `/started-ms`, `/ended-ms`; `:seon.process/pid`, `/exit`, `/ended-ms` | kept |

## Before you add something

Four questions settle most new data, and each can end with adding nothing.

1. **What reads it?** If nothing does, it is not stored; if it is already an attribute or a query away, use that.
2. **Whose fact is it?** Put it on the entity it is about, on the many side of a relationship.
3. **How will it be found?** By name: an identity. By a shared value: `{:seon.db/index true}`. By its entity: nothing more.
4. **Does a change cost what changed?** Write down what a new member costs, and prove it with a [cost proof](cost.md) if its input grows.

The shape is ours to change: the standing store is test data, so a rename needs no migration ([machine](machine.md#disk-stays-bounded)).
