# Users

The design for users, worlds within worlds: not built yet, and the one place the three sessions read it from; each claim says whether it ran (lab), was read from code (read) or is untested.

A user is an agent, a session is a branch of its user's branch, and the system is one mechanism used in different data configurations. State is always in the database and the system is a function of that state: there are no runs and no objects, only entities, attributes and refs. Owner: the Web session. Words are git's and Clojure's ([vocabulary](vocabulary.md)).

## What each thing is

A thing is its data structure: any entity holding the shape is that thing, however it got there, and where each part is stored is then ours to choose.

- **User:** an agent entity with a key like ``sean``, so ``[:seon.agent/key "sean"]`` is its lookup ref, and a branch. Same key, same entity: login decides later who may be ``sean``. It has a process only while something needs one, and it can run with a model to act for its person, recording like any agent. Marking each record as the person's or their agent's is open.
- **Who points at what:** ``:seon.agent/agents``, a set of refs on the user, on main. Many users can point at one agent; there is no owner attribute; removing a ref keeps its history. Windows over it must order by when each joined, since agent ids are content-derived (lab: the newest fell out of a window of 20 at 1,000).
- **Session:** an agent on a temp branch of its user's branch.
- **Eval:** an entity attached to its agent: code, intent, cost, namespace, and ``:seon.eval/result``, a ref. Its id is its eid, which the store issues uniquely across branches (lab, read). Evaluating again moves the ref, and the context regenerates.
- **Result:** one immutable entity per value, the same on every branch: ``:seon.result/hash``, the full hash as identity and content id, the lookup ref; ``:seon.result/value``, ``:ex``, ``:out``, ``:err``. The wire keeps the short handle, resolved by a seek: the identity must be the full hash, or two values sharing a prefix would become one entity.
- **Layout:** a data structure naming existing components, on its own key, as root's already is. Functions remain for what data can't express.

## Where each fact lives

Each fact lives where the readers who need it find it, and that placement is what removes guards and what makes reads lazy.

- **Main:** that an agent or user exists, its branch and upstream, who points at what, and messages addressed to a branch. Delivery is the fact.
- **A branch:** evals and a person's data and functions.
- **Every branch alike:** content-id entities, forms, results and messages, so forks share them and they can't conflict.
- **Merge bases:** two facts per branch: a program base moved by a pull, and a data base left at the fork or last merge. One base loses data: a session pulling after another session's merge would move its base past data it lacks, and its next merge reads as retracting it (Optimization, read).
- **Live process state:** main only, root's observation.
- **Laziness comes from placement:** reading 300 evals' code cold with 60 KB values took 100-125 ms inline and 3 ms with a content-id result, the same as no large values; following the ref took about 1 ms (lab, one run). A node load realizes every large value in it, so a value next to what you read gets read. Identical values never share today, because ``:seon.eval/response`` mixes the value with ``:ms`` and ``:ticks`` (lab).

## Four interfaces

There are four ways to touch this system, and no interface writes another process's branch, so the guards that would protect against that have nothing to do.

- **Say something:** a message fact, to any agent in any state.
- **Evaluate:** the agent's REPL. A person's input and a model's reply are the same shape: ``seon.reader/forgive`` turns text into a message and evals, with a ``;;`` line above a form as its optional intent. Words wake an agent; a form doesn't.
- **Read:** ``@ctx``, a pull on an entity. An agent's for its context, a user's for its home page.
- **Merge:** one pure function of references, never db values: target, source and base in ``:seon.db/at-opts``' shape, read by ``seon.db/at``, and options. It answers tx-data, written by whoever writes the target.

## The merges

One merge function takes different options for each case, which is what makes this one system rather than several.

- **Merge a session into its user's branch:** every datom that doesn't conflict, data, code and transcript, by a person's decision. Data and code mix here and only here. Until then the session is a branch nobody needs to delete.
- **Pull:** main into a user's branch and on to its sessions: program only, schemas, functions, tests and docs, ``data-attrs #{}``. So every branch has the same program and different data. A session takes its user's data at the fork and only program afterwards.
- **Merge to main:** an agent's work through the gate, per agent's work. A person decides when a session's code goes.
- **Options:** which keys (a cherry-pick has no second parent and doesn't move the base), ``-X theirs`` or ``-X ours``, and which data. A curated send of one namespace of two, and main's version winning a conflict, both worked (lab).
- **A refused merge is a normal step:** main moved, the error says so, the agent merges main and retries.

Whole merges retract nothing: agent to user to main to user, forms and data, both pull orders, twice (lab). Criss-cross goes away with the base as a fact; either ancestor gave the same merge (lab).

## Lazy

Be lazy about whether work happens, never about when within a step: decide at a boundary, an agent's start, a turn or a read of a part, then do the work there.

A lazy value realized later can pin a db (160 MB per proc, measured), miss read recording and so give a false green, throw past the catch that names the failure, or charge the wrong eval. So:

- **An agent's first act is to merge.** Nothing merges on a branch with no agent, and dead branches cost nothing. Merging once when needed took a third of the time of merging every time (lab: 6.1 s against 17.5 s over 200 merges).
- **A conflict** surfaces as unmerged entries and can launch an agent, under a spend limit, when a process next exists on that branch.
- **"Behind"** is a function of state, readable with no process, so a page can say so.

## Sharing

A branch should be main plus its own changes, so everything is a fork of a shared base; today's merges don't keep it that way, because a merge writes its own copy of each leaf.

- After 200 program merges, 84% of a user's index was its own (lab, real merges, 1,080 forms).
- Content addressing got 12-15% merged every time but 59% merged once, and a write phase went from 29 s to 52 s, so it's not recommended (lab).
- Rebase keeps a branch's own nodes flat, tracking its data: about 885 to 930 at 5,000 entities, about 6.2 s each, flat in how far behind; a retraction, an own function and an old commit all survived (lab). As a lazy step on a count, checked at an agent's start, a branch nobody uses never pays.
- Rebase isn't proved safe: it sits on main's whole head, so it takes main's platform records too, and a message's presence on a branch is its delivery, so a message could read as delivered before it's taken (read). Optimization's tree merge has the same question. Optimization owns the choice: see task ``seon.db/a-branch-is-main-plus-its-own-changes``.

## When a definition changes under data

A definition's change and the data it leaves behind are one merge result, so a branch either fits or says what doesn't, and it can't merge up until it does.

What happens today (lab): a type change is refused where the attribute is stored; main narrowing ``:int`` to ``[:int {:min 100}]`` merges clean and leaves ``3`` unreported; narrowing on your own branch reports 4 of 4 refused and registers anyway.

- For each schema key a merge moved or a registration changed, read that attribute's values (``usage-check``). Each that no longer fits becomes an unmerged entry, which the status and done rule already handle.
- A definition can carry its migration, a program var old to new, applied first, so easy cases cost no agent.
- What can't be migrated is a new attribute, as a type change already is. Widening needs nothing.
- Untested: a required key added to an entity schema, and a real agent resolving these from its status alone.

## Correct

A branch is correct when, read on its value alone, every datom fits its schema, every form loads and has its contract, and what's unmerged is listed as data.

A commit is one transaction, so a branch is never half-merged, and one behind main is a consistent older value that knows it's behind. A pull brings a schema and the functions using it in one commit, and loads changed forms into a live process in the same step: a live function returned 1, its agent could see main had moved, and it returned 2 only after merging (lab).

## The steps

The steps below merge one at a time, each usable alone, with today's agents unchanged by proof; tasks are written once the data model has been tried at the REPL.

1. Fix what survives the plan: the write check on an agent's own attributes, a bare attribute's declaration through a merge.
2. Evals with their eid, and shared results.
3. Merge bases as two facts, and merge options.
4. An agent's first act is to merge; an agent with untaken messages wants a process.
5. Users, and input through ``forgive``.
6. Sessions and merging them.
7. Rebase or the tree merge, whichever Optimization's comparison picks.
8. The home page from the user's ``@ctx``, with layouts as data.

Out for now: one user's renders drawing another's agents, login, teams. Depends on Context's ``seon.source/seon-files-move-by-branch``, since branches don't isolate behaviour until a running process reads its config from the db, and on Optimization's digest work for what's reused across branches.

## Tasks and proofs

Tasks are small and their proofs are ours, written and watched red before release, so that a Seon agent or a Claude agent over MCP can work them (Context, from real tasks; a small sample).

- One behaviour, one place, an exact reproduction with what it answers now and what it should, under about 600 characters, saying what's out of scope. A task needing a design decision isn't an agent's task yet.
- The agent may add proofs: ours is the floor. Proofs stated on data with exact values, on small worlds, with no process where the claim allows.
- One process test per mechanism, written last. What changes what's running splits into a pure part and a live part in a worktree.
- A generative suite comes after the first merge tasks: random merge, pull, rebase, cherry-pick and schema-change sequences checked against invariants, shrinking to the op sequence that broke. A "no problems" check is an extra assertion, never the only proof.
